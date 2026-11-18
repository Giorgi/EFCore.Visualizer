package efcore.visualizer

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.jetbrains.rider.debugger.settings.DotNetDebuggerSettings
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTree
import com.intellij.xdebugger.impl.ui.tree.actions.XDebuggerTreeActionBase
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import efcore.visualizer.QueryPlanVisualizer.unwrap

/** "Visualize Query Plan" in the debugger Variables/Watches context menu. */
class VisualizeQueryPlanAction : DumbAwareAction() {
    companion object {
        const val ID = "EFCoreVisualizer.VisualizeQueryPlan"

        /** EF Core's query types, as the VS Code extension matches them: a query, an Include, a DbSet. */
        private val QUERY_TYPE = Regex("EntityQueryable|IncludableQueryable|InternalDbSet")

        /** A .NET debug session (Rider also debugs JavaScript, C++, Python, ...). */
        private fun isDotNet(session: XDebugSession) =
            generateSequence<Class<*>>(session.debugProcess.javaClass) { it.superclass }
                .any { it.name == "com.jetbrains.rider.debugger.DotNetDebugProcess" }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    /** The event's project, or the debugger tree's own (some contexts lack it). */
    private fun projectOf(e: AnActionEvent) = e.project ?: XDebuggerTree.getTree(e.dataContext)?.project

    /**
     * The session the clicked view belongs to. The context doesn't always say (it depends on the Rider version and
     * where the menu was opened); then the active session, which is the one whose views are shown.
     */
    private fun sessionOf(e: AnActionEvent, project: Project): XDebugSession? =
        e.getData(XDebugSession.DATA_KEY) ?: XDebuggerManager.getInstance(project).currentSession

    /**
     * Only for EF Core queries in a paused .NET session: on anything else, the action would load the helper into the
     * app for nothing. While a value is still being computed, the action stays available.
     *
     * In a context menu it's hidden on anything else. On the Variables view's toolbar, it stays in place in every
     * .NET session and is only greyed out, so the toolbar doesn't change as the selection moves.
     */
    override fun update(e: AnActionEvent) {
        val session = projectOf(e)?.let { sessionOf(e, it) }
        val node = XDebuggerTreeActionBase.getSelectedNode(e.dataContext)
        // Rider shows a DbSet as "{InternalDbSet<Blog>} ...", but an EF query only as its value: "EntityQueryable<Blog>".
        val shown = node?.valuePresentation?.type ?: node?.rawValue
        val dotNet = session != null && isDotNet(session)
        val enabled = dotNet && session!!.isSuspended && node != null && (shown == null || QUERY_TYPE.containsMatchIn(shown))
        if (e.isFromContextMenu) {
            e.presentation.isEnabledAndVisible = enabled
        } else {
            e.presentation.isVisible = dotNet
            e.presentation.isEnabled = enabled
        }
    }

    /** Variables whose plan is being fetched; UI thread only. A second click meanwhile is ignored: starting another run
     * would run the query twice. */
    private val running = HashSet<Pair<XDebugSession, String>>()

    override fun actionPerformed(e: AnActionEvent) {
        val project = projectOf(e) ?: return
        val session = sessionOf(e, project) ?: return
        val node = XDebuggerTreeActionBase.getSelectedNode(e.dataContext) ?: return
        val presenter = service<QueryPlanPresenter>()
        val app = ApplicationManager.getApplication()

        // The helper is called like a method in Watches: Rider refuses that with this setting off ("Implicit evaluation
        // is disabled").
        if (!DotNetDebuggerSettings.instance.allowImplicitTargetInvoke) {
            presenter.notify(project, "Turn on \"Allow property evaluations and other implicit function calls\" in " +
                "Settings | Build, Execution, Deployment | Debugger: the visualizer calls methods in the app to get the plan.", NotificationType.ERROR)
            return
        }

        node.valueContainer.calculateEvaluationExpression().onProcessed { xExpression ->
            // No guessing from the node's name: for a member of another value, that name alone means something else.
            val expression = xExpression?.expression?.takeIf { it.isNotBlank() }
            app.invokeLater {
                when {
                    project.isDisposed -> {}
                    expression == null -> presenter.notify(project, "Can't build an expression for the selected value.", NotificationType.ERROR)
                    running.add(session to expression) -> visualize(project, session, expression, presenter)
                }
            }
        }
    }

    /** Like the VS Code extension: if the variable's tab shows the plan for the query's current SQL, just switch to it. */
    private fun visualize(project: Project, session: XDebugSession, expression: String, presenter: QueryPlanPresenter) {
        val app = ApplicationManager.getApplication()
        val variable = session to expression
        // One indicator for the whole run, the SQL check included: the first time, loading the helper into the app and
        // getting the SQL alone take a few seconds.
        val done = CompletableFuture<Unit>()
        PlanProgress.whileRunning(project, expression, done)
        QueryPlanVisualizer.queryString(session, expression).whenComplete { sql, _ ->
            app.invokeLater {
                val unchanged = try {
                    project.isDisposed || presenter.focusIfUnchanged(project, session, expression, sql)
                } catch (t: Throwable) {
                    // The indicator and the click guard mustn't stay forever.
                    running.remove(variable)
                    done.complete(Unit)
                    throw t
                }
                if (unchanged) {
                    running.remove(variable)
                    done.complete(Unit)
                    return@invokeLater
                }
                QueryPlanVisualizer.visualize(session, expression).whenComplete { result, error ->
                    done.complete(Unit)
                    app.invokeLater {
                        running.remove(variable)
                        when {
                            // Nobody will see the page: don't leave it in the temp folder.
                            project.isDisposed -> result?.let { Files.deleteIfExists(it.file) }
                            error != null -> presenter.notify(project, error.unwrap().message ?: error.toString(), NotificationType.ERROR)
                            // Written on the machine the app runs on, which isn't this one.
                            !Files.isRegularFile(result.file) ->
                                presenter.notify(project, "The page was written where the app runs, as ${result.file}. ${QueryPlanVisualizer.REMOTE_HINT}", NotificationType.ERROR)
                            else -> presenter.show(project, result, expression, session, sql)
                        }
                    }
                }
            }
        }
    }
}
