package efcore.visualizer

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.Utils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.TestActionEvent
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTree
import com.intellij.xdebugger.impl.ui.tree.actions.XDebuggerTreeActionBase
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueContainerNode
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl
import com.jetbrains.rider.debugger.settings.DotNetDebuggerSettings
import com.jetbrains.rider.test.scriptingApi.resumeSession
import com.jetbrains.rider.test.scriptingApi.waitForPause
import com.intellij.xdebugger.XDebugSession
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Risk 1 from the spike: does the menu action itself work on a real debugger tree selection?
 * Drives VisualizeQueryPlanAction exactly as the Variables context menu would: registered in XDebugger.ValueGroup,
 * update() on a selected "query" node, then actionPerformed(). Only the presenter (browser tab) is replaced.
 */
abstract class VisualizeActionTestBase : SampleAppDebuggerTestBase() {

    /** Records what the action asks for. [tabSql]: the SQL of a plan tab the variable already has, if any. */
    private class RecordingPresenter(private val tabSql: String? = null) : QueryPlanPresenter() {
        val checked = CompletableFuture<String?>()
        val shown = CompletableFuture<Triple<VisualizeResult, String, String?>>()
        val shows = AtomicInteger()
        override fun focusIfUnchanged(project: Project, session: XDebugSession, expression: String, sql: String?): Boolean {
            checked.complete(sql)
            return tabSql != null && sql == tabSql
        }
        override fun show(project: Project, result: VisualizeResult, expression: String, session: XDebugSession, sql: String?) {
            shows.incrementAndGet()
            shown.complete(Triple(result, expression, sql))
        }
        override fun notify(project: Project, message: String, type: NotificationType) {
            val error = AssertionError("Action reported $type instead of showing a page: $message")
            checked.completeExceptionally(error)
            shown.completeExceptionally(error)
        }
    }

    /** Dispatches UI events for [seconds], so work the action may still start gets the chance to. */
    private fun settle(seconds: Long = 3) {
        val until = System.currentTimeMillis() + seconds * 1000
        PlatformTestUtil.waitWithEventsDispatching("settle", { System.currentTimeMillis() > until }, seconds.toInt() + 5)
    }

    // Called from the version-specific @Test in VisualizeActionTest (Rider reads test annotations from the declaring class).
    protected fun runVariablesContextMenuActionShowsPlanForSelectedQuery() {
        // The visualizer relies on method calls in watches; Rider's own default allows them (the test framework
        // switches them off for its tests, hence stopAtBreakpoint).
        assertTrue(DotNetDebuggerSettings().allowImplicitTargetInvoke, "Rider's default disallows method calls in watches")

        testDebugProgram({ stopAtBreakpoint() }, {
            waitForPause()

            // 1. Registered in the debugger value context menu.
            val actionManager = ActionManager.getInstance()
            val action = assertNotNull(actionManager.getAction(VisualizeQueryPlanAction.ID), "action not registered")
            val valueGroup = actionManager.getAction("XDebugger.ValueGroup") as DefaultActionGroup
            assertTrue(
                valueGroup.childActionsOrStubs.any { actionManager.getId(it) == VisualizeQueryPlanAction.ID },
                "action is not in XDebugger.ValueGroup",
            )

            // 2. The session's real Variables view.
            val tree = assertNotNull(RiderCompat.variablesTree(session), "the debug session has no Variables view")

            var queryNode: XValueNodeImpl? = null
            PlatformTestUtil.waitWithEventsDispatching("'query' did not appear in the variables tree", {
                val root = tree.root as? XValueContainerNode<*>
                queryNode = root?.children?.filterIsInstance<XValueNodeImpl>()?.firstOrNull { it.name == "query" }
                // Wait for its value too, so update() checks it (an unknown value would keep the action enabled anyway).
                queryNode?.rawValue != null
            }, 60)
            val node = queryNode!!
            println("Selected node: ${node.name} (${node.valueContainer.javaClass.name}), shown as: ${node.rawValue}")
            tree.selectionPath = node.path

            // Built from the tree component the same way the action system builds a context-menu context,
            // so the data (XDEBUGGER_TREE_KEY, SELECTED_NODES in split-debugger mode) comes from the tree itself.
            val dataContext = Utils.createAsyncDataContext(tree)
            assertEquals(listOf("query"), XDebuggerTree.SELECTED_NODES.getData(dataContext)?.map { it.name })

            // 3. update(): enabled for the selected value while suspended.
            val event = TestActionEvent.createTestEvent(action, dataContext)
            println("Selected value resolves to: ${XDebuggerTreeActionBase.getSelectedValue(dataContext)?.javaClass?.name}")
            action.update(event)
            assertTrue(event.presentation.isEnabledAndVisible, "action is disabled/hidden for the selected 'query' node")

            // 4. actionPerformed(): evaluation expression -> helper -> plan, handed to the presenter. Clicked twice: the
            //    second click, while the first one runs, must not get (and run) the query again.
            val presenter = RecordingPresenter()
            val disposable = Disposer.newDisposable()
            val planSql: String
            try {
                ApplicationManager.getApplication().replaceService(QueryPlanPresenter::class.java, presenter, disposable)
                action.actionPerformed(event)
                action.actionPerformed(event)
                val (result, expression, sql) = PlatformTestUtil.waitForFuture(presenter.shown, 60_000)
                println("Action showed: $result for '$expression', SQL: $sql")
                assertEquals("query", expression)
                assertEquals(VisualizeResult.Kind.PLAN, result.kind)
                assertContains(result.file.readText(), Regex("SEARCH \\w+ USING INDEX IX_Blogs_Rating"))
                planSql = assertNotNull(sql, "no SQL for the plan")
                assertContains(planSql, "FROM \"Blogs\"")
                result.file.deleteIfExists()
                settle()
                assertEquals(1, presenter.shows.get(), "a second click got the plan again")
            } finally {
                Disposer.dispose(disposable)
            }

            // 4b. Again, with the variable's tab already showing the plan for this SQL: only that tab is focused.
            val reusing = RecordingPresenter(tabSql = planSql)
            val reuseDisposable = Disposer.newDisposable()
            try {
                ApplicationManager.getApplication().replaceService(QueryPlanPresenter::class.java, reusing, reuseDisposable)
                action.actionPerformed(event)
                assertEquals(planSql, PlatformTestUtil.waitForFuture(reusing.checked, 60_000), "the SQL changed between two clicks")
                settle()
                assertEquals(0, reusing.shows.get(), "an up-to-date tab got a new plan")
            } finally {
                Disposer.dispose(reuseDisposable)
            }

            // 4c. The app loaded the helper from its per-version copy, not from the plugin's folder (HelperCopy).
            val location = PlatformTestUtil.waitForFuture(DebuggeeEvaluator.evaluate(session,
                "typeof(${DebuggeeEvaluator.HELPER_TYPE}).Assembly.Location"), 60_000)
            println("Helper loaded from: $location")
            val copies = java.nio.file.Path.of(com.intellij.openapi.application.PathManager.getSystemPath(), "efcore-visualizer").toString()
            assertContains(location, copies)

            // 4d. With method calls in watches off, the action says which setting to turn on.
            val refused = RecordingPresenter()
            val refusedDisposable = Disposer.newDisposable()
            try {
                ApplicationManager.getApplication().replaceService(QueryPlanPresenter::class.java, refused, refusedDisposable)
                DotNetDebuggerSettings.instance.allowImplicitTargetInvoke = false
                action.actionPerformed(event)
                val message = runCatching { PlatformTestUtil.waitForFuture(refused.checked, 60_000) }.exceptionOrNull()
                    ?.let { e -> generateSequence(e) { it.cause }.mapNotNull { it.message }.firstOrNull { "Allow property" in it } }
                println("With method calls off: $message")
                assertContains(message.orEmpty(), "Allow property evaluations and other implicit function calls")
            } finally {
                DotNetDebuggerSettings.instance.allowImplicitTargetInvoke = true
                Disposer.dispose(refusedDisposable)
            }

            // 5. Offered for a DbSet and an Include; hidden for values that aren't EF Core queries.
            for ((name, offered) in listOf("blogs" to true, "included" to true, "numbers" to false, "list" to false, "args" to false)) {
                var other: XValueNodeImpl? = null
                PlatformTestUtil.waitWithEventsDispatching("'$name' did not appear in the variables tree", {
                    other = (tree.root as? XValueContainerNode<*>)?.children?.filterIsInstance<XValueNodeImpl>()?.firstOrNull { it.name == name }
                    other?.rawValue != null
                }, 60)
                tree.selectionPath = other!!.path
                val otherEvent = TestActionEvent.createTestEvent(action, Utils.createAsyncDataContext(tree))
                action.update(otherEvent)
                println("'$name' (${other!!.valuePresentation?.type} / ${other!!.rawValue}): enabled=${otherEvent.presentation.isEnabledAndVisible}")
                assertEquals(offered, otherEvent.presentation.isEnabledAndVisible, "action offered for '$name'")
            }

            resumeSession()
        }, true)
    }
}
