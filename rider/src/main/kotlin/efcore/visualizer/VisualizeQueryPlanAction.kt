package efcore.visualizer

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.impl.HTMLEditorProvider
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.impl.ui.tree.actions.XDebuggerTreeActionBase
import efcore.visualizer.QueryPlanVisualizer.unwrap
import java.nio.file.Files

/** "Visualize Query Plan" in the debugger Variables/Watches context menu. */
class VisualizeQueryPlanAction : DumbAwareAction() {
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
        node.valueContainer.calculateEvaluationExpression().onSuccess { xExpression ->
            val expression = xExpression?.expression ?: node.name
            if (expression.isNullOrBlank()) {
                notify(project, "Cannot build an expression for the selected value.", NotificationType.ERROR)
                return@onSuccess
            }
            QueryPlanVisualizer.visualize(session, expression).whenComplete { result, error ->
                ApplicationManager.getApplication().invokeLater {
                    when {
                        error != null -> notify(project, error.unwrap().message ?: error.toString(), NotificationType.ERROR)
                        else -> {
                            result.warning?.let { notify(project, it, NotificationType.WARNING) }
                            open(project, result, expression)
                        }
                    }
                }
            }
        }
    }

    private fun open(project: Project, result: VisualizeResult, expression: String) {
        val title = if (result.kind == VisualizeResult.Kind.PLAN) "Query Plan: $expression" else "Query: $expression"
        // A file:// URL keeps the templates' relative CSS/JS references working.
        val editor = HTMLEditorProvider.openEditor(project, title, HTMLEditorProvider.Request.url(result.file.toUri().toString()))
        if (editor == null) {
            notify(project, "Could not open an embedded browser tab. The page is at ${result.file}", NotificationType.WARNING)
            return
        }
        Disposer.register(editor) { Files.deleteIfExists(result.file) }
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("EFCore Visualizer")
            .createNotification("EFCore Visualizer", message, type)
            .notify(project)
    }
}
