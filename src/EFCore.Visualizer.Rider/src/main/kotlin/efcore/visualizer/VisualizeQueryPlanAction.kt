package efcore.visualizer

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTree
import com.intellij.xdebugger.impl.ui.tree.actions.XDebuggerTreeActionBase
import java.nio.file.Files
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

    /** The event's project, or the debugger tree's own (some contexts, e.g. Rider 2025.1 without a frame, lack it). */
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
     */
    override fun update(e: AnActionEvent) {
        val session = projectOf(e)?.let { sessionOf(e, it) }
        val node = XDebuggerTreeActionBase.getSelectedNode(e.dataContext)
        // Rider shows a DbSet as "{InternalDbSet<Blog>} ...", but an EF query only as its value: "EntityQueryable<Blog>".
        val shown = node?.valuePresentation?.type ?: node?.rawValue
        e.presentation.isEnabledAndVisible = session != null && session.isSuspended && isDotNet(session) && node != null &&
            (shown == null || QUERY_TYPE.containsMatchIn(shown))
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = projectOf(e) ?: return
        val session = sessionOf(e, project) ?: return
        val node = XDebuggerTreeActionBase.getSelectedNode(e.dataContext) ?: return
        val presenter = service<QueryPlanPresenter>()
        // Results arrive on a background thread; show them on the UI thread, unless the project closed meanwhile.
        fun onUiThread(block: () -> Unit) = ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) block() }

        node.valueContainer.calculateEvaluationExpression().onProcessed { xExpression ->
            // No guessing from the node's name: for a member of another value, that name alone means something else.
            val expression = xExpression?.expression?.takeIf { it.isNotBlank() }
            if (expression == null) {
                onUiThread { presenter.notify(project, "Can't build an expression for the selected value.", NotificationType.ERROR) }
                return@onProcessed
            }
            QueryPlanVisualizer.visualize(session, expression).whenComplete { result, error ->
                ApplicationManager.getApplication().invokeLater {
                    when {
                        // Nobody will see the page: don't leave it in the temp folder.
                        project.isDisposed -> result?.let { Files.deleteIfExists(it.file) }
                        error != null -> presenter.notify(project, error.unwrap().message ?: error.toString(), NotificationType.ERROR)
                        else -> presenter.show(project, result, expression)
                    }
                }
            }
        }
    }
}
