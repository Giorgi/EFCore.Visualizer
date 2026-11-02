package efcore.visualizer

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.impl.ui.tree.actions.XDebuggerTreeActionBase
import efcore.visualizer.QueryPlanVisualizer.unwrap

/** "Visualize Query Plan" in the debugger Variables/Watches context menu. */
class VisualizeQueryPlanAction : DumbAwareAction() {
    companion object {
        const val ID = "EFCoreVisualizer.VisualizeQueryPlan"
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val session = project?.let { XDebuggerManager.getInstance(it).currentSession }
        e.presentation.isEnabledAndVisible =
            session != null && session.isSuspended && XDebuggerTreeActionBase.getSelectedValue(e.dataContext) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val session = XDebuggerManager.getInstance(project).currentSession ?: return
        val node = XDebuggerTreeActionBase.getSelectedNode(e.dataContext) ?: return
        val presenter = service<QueryPlanPresenter>()
        node.valueContainer.calculateEvaluationExpression().onProcessed { xExpression ->
            val expression = xExpression?.expression?.takeIf { it.isNotBlank() } ?: node.name
            if (expression.isNullOrBlank()) {
                ApplicationManager.getApplication().invokeLater {
                    presenter.notify(project, "Cannot build an expression for the selected value.", NotificationType.ERROR)
                }
                return@onProcessed
            }
            QueryPlanVisualizer.visualize(session, expression).whenComplete { result, error ->
                ApplicationManager.getApplication().invokeLater {
                    if (error != null) presenter.notify(project, error.unwrap().message ?: error.toString(), NotificationType.ERROR)
                    else presenter.show(project, result, expression)
                }
            }
        }
    }
}
