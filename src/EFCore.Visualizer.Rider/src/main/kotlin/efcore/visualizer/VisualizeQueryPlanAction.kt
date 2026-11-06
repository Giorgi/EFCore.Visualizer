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
import efcore.visualizer.QueryPlanVisualizer.unwrap

/** "Visualize Query Plan" in the debugger Variables/Watches context menu. */
class VisualizeQueryPlanAction : DumbAwareAction() {
    companion object {
        const val ID = "EFCoreVisualizer.VisualizeQueryPlan"
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

    override fun update(e: AnActionEvent) {
        val session = projectOf(e)?.let { sessionOf(e, it) }
        e.presentation.isEnabledAndVisible =
            session != null && session.isSuspended && XDebuggerTreeActionBase.getSelectedValue(e.dataContext) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = projectOf(e) ?: return
        val session = sessionOf(e, project) ?: return
        val node = XDebuggerTreeActionBase.getSelectedNode(e.dataContext) ?: return
        val presenter = service<QueryPlanPresenter>()
        // Results arrive on a background thread; show them on the UI thread, unless the project closed meanwhile.
        fun onUiThread(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block, project.disposed)

        node.valueContainer.calculateEvaluationExpression().onProcessed { xExpression ->
            // No guessing from the node's name: for a member of another value, that name alone means something else.
            val expression = xExpression?.expression?.takeIf { it.isNotBlank() }
            if (expression == null) {
                onUiThread { presenter.notify(project, "Can't build an expression for the selected value.", NotificationType.ERROR) }
                return@onProcessed
            }
            QueryPlanVisualizer.visualize(session, expression).whenComplete { result, error ->
                onUiThread {
                    if (error != null) presenter.notify(project, error.unwrap().message ?: error.toString(), NotificationType.ERROR)
                    else presenter.show(project, result, expression)
                }
            }
        }
    }
}
