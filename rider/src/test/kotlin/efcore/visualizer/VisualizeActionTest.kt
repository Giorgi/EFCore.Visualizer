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
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTree
import com.intellij.xdebugger.impl.ui.tree.actions.XDebuggerTreeActionBase
import com.intellij.xdebugger.impl.ui.tree.nodes.XStackFrameNode
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueContainerNode
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl
import com.jetbrains.rider.debugger.settings.DotNetDebuggerSettings
import com.jetbrains.rider.test.annotations.Solution
import com.jetbrains.rider.test.annotations.TestSettings
import com.jetbrains.rider.test.base.DebuggerTestBase
import com.jetbrains.rider.test.debugger.disableTargetInvokeWithWatches
import com.jetbrains.rider.test.debugger.enableTargetInvokeWithWatches
import com.jetbrains.rider.test.enums.BuildTool
import com.jetbrains.rider.test.enums.Mono
import com.jetbrains.rider.test.enums.sdk.SdkVersion
import com.jetbrains.rider.test.scriptingApi.resumeSession
import com.jetbrains.rider.test.scriptingApi.toggleBreakpoint
import com.jetbrains.rider.test.scriptingApi.waitForPause
import org.testng.annotations.AfterMethod
import org.testng.annotations.Test
import java.util.concurrent.CompletableFuture
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
@TestSettings(sdkVersion = SdkVersion.AUTODETECT, buildTool = BuildTool.AUTODETECT, mono = Mono.NONE)
@Solution("SampleApp")
class VisualizeActionTest : DebuggerTestBase() {
    override val projectName = "SampleApp"

    private class RecordingPresenter : QueryPlanPresenter() {
        val shown = CompletableFuture<Pair<VisualizeResult, String>>()
        override fun show(project: Project, result: VisualizeResult, expression: String) {
            shown.complete(result to expression)
        }
        override fun notify(project: Project, message: String, type: NotificationType) {
            shown.completeExceptionally(AssertionError("Action reported $type instead of showing a page: $message"))
        }
    }

    @Test
    fun variablesContextMenuActionShowsPlanForSelectedQuery() {
        val programCs = activeSolutionDirectory.resolve("SampleApp/Program.cs").toFile()
        val line = programCs.readLines().indexOfFirst { "// BREAKPOINT" in it } + 1

        // The visualizer relies on method calls in watches; Rider's own default allows them (the test framework
        // switches them off for its tests, hence enableTargetInvokeWithWatches below).
        assertTrue(DotNetDebuggerSettings().allowImplicitTargetInvoke, "Rider's default disallows method calls in watches")

        testDebugProgram({
            DotNetDebuggerSettings.instance.enableTargetInvokeWithWatches()
            toggleBreakpoint(project, "SampleApp/Program.cs", line)
        }, {
            waitForPause()

            // 1. Registered in the debugger value context menu.
            val actionManager = ActionManager.getInstance()
            val action = assertNotNull(actionManager.getAction(VisualizeQueryPlanAction.ID), "action not registered")
            val valueGroup = actionManager.getAction("XDebugger.ValueGroup") as DefaultActionGroup
            assertTrue(
                valueGroup.childActionsOrStubs.any { actionManager.getId(it) == VisualizeQueryPlanAction.ID },
                "action is not in XDebugger.ValueGroup",
            )

            // 2. A real debugger tree: the session's Variables view if the test framework created one.
            val realTree = (session as? XDebugSessionImpl)?.sessionTabIfInitialized?.variablesView?.tree
            val tree = realTree ?: XDebuggerTree(project, session.debugProcess.editorsProvider, null, "XDebugger.ValueGroup", null).also {
                it.setRoot(XStackFrameNode(it, session.currentStackFrame!!, null), false)
            }
            println("Variables tree: ${if (realTree != null) "session Variables view" else "XDebuggerTree over current frame"}")

            var queryNode: XValueNodeImpl? = null
            PlatformTestUtil.waitWithEventsDispatching("'query' did not appear in the variables tree", {
                val root = tree.root as? XValueContainerNode<*>
                queryNode = root?.children?.filterIsInstance<XValueNodeImpl>()?.firstOrNull { it.name == "query" }
                queryNode != null
            }, 60)
            val node = queryNode!!
            println("Selected node: ${node.name} (${node.valueContainer.javaClass.name})")
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

            // 4. actionPerformed(): evaluation expression -> helper -> plan, handed to the presenter.
            val presenter = RecordingPresenter()
            val disposable = Disposer.newDisposable()
            try {
                ApplicationManager.getApplication().replaceService(QueryPlanPresenter::class.java, presenter, disposable)
                action.actionPerformed(event)
                val (result, expression) = PlatformTestUtil.waitForFuture(presenter.shown, 60_000)
                println("Action showed: $result for '$expression'")
                assertEquals("query", expression)
                assertEquals(VisualizeResult.Kind.PLAN, result.kind)
                assertContains(result.file.readText(), Regex("SEARCH \\w+ USING INDEX IX_Blogs_Rating"))
                result.file.deleteIfExists()
            } finally {
                Disposer.dispose(disposable)
            }

            resumeSession()
        }, true)
    }

    @AfterMethod(alwaysRun = true)
    fun restoreDebuggerSettings() {
        DotNetDebuggerSettings.instance.disableTargetInvokeWithWatches()
    }
}
