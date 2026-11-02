package efcore.visualizer

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.impl.HTMLEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.nio.file.Files

/**
 * Shows results and messages. A regular application service registered in plugin.xml (light @Service classes
 * must be final), so tests can replace it.
 */
open class QueryPlanPresenter {
    open fun show(project: Project, result: VisualizeResult, expression: String) {
        result.warning?.let { notify(project, it, NotificationType.WARNING) }
        val title = if (result.kind == VisualizeResult.Kind.PLAN) "Query Plan: $expression" else "Query: $expression"
        // A file:// URL keeps the templates' relative CSS/JS references working.
        val editor = HTMLEditorProvider.openEditor(project, title, HTMLEditorProvider.Request.url(result.file.toUri().toString()))
        if (editor == null) {
            notify(project, "Could not open an embedded browser tab. The page is at ${result.file}", NotificationType.WARNING)
            return
        }
        Disposer.register(editor) { Files.deleteIfExists(result.file) }
    }

    open fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("EFCore Visualizer")
            .createNotification("EFCore Visualizer", message, type)
            .notify(project)
    }
}
