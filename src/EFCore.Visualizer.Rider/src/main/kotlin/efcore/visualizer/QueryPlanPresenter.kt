package efcore.visualizer

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.impl.HTMLEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.xml.util.XmlStringUtil
import java.nio.file.Files

/**
 * Shows results and messages. A regular application service registered in plugin.xml (light @Service classes
 * must be final), so tests can replace it.
 */
open class QueryPlanPresenter {
    open fun show(project: Project, result: VisualizeResult, expression: String) {
        result.warning?.let { notify(project, it, NotificationType.WARNING) }
        val shortExpression =
            if (expression.codePointCount(0, expression.length) > 40) expression.substring(0, expression.offsetByCodePoints(0, 39)) + "…"
            else expression
        val title = if (result.kind == VisualizeResult.Kind.PLAN) "Query Plan: $shortExpression" else "Query: $shortExpression"
        // A file:// URL keeps the templates' relative CSS/JS references working.
        val editor = HTMLEditorProvider.openEditor(project, title, HTMLEditorProvider.Request.url(result.file.toUri().toString()))
        if (editor == null) {
            notify(project, "Could not open an embedded browser tab. The page is at ${result.file}", NotificationType.WARNING)
            return
        }
        Disposer.register(editor) { Files.deleteIfExists(result.file) }
    }

    open fun notify(project: Project, message: String, type: NotificationType) {
        val group = if (type == NotificationType.ERROR) "EFCore Visualizer errors" else "EFCore Visualizer"
        NotificationGroupManager.getInstance().getNotificationGroup(group)
            // The content is HTML: escape it, or "DbSet<Blog>" and "Rating < 3" in an EF error would be cut off.
            .createNotification("EFCore Visualizer", XmlStringUtil.escapeString(message).replace("\n", "<br>"), type)
            .notify(project)
    }
}
