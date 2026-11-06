package efcore.visualizer

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.HTMLEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.xdebugger.XDebugSession
import com.intellij.xml.util.XmlStringUtil
import java.nio.file.Files

/**
 * Shows results and messages. A regular application service registered in plugin.xml (light @Service classes
 * must be final), so tests can replace it.
 *
 * Like the VS Code extension, each variable (an expression in a debug session) has one tab: visualizing it again
 * shows the new page in that tab, or only switches to it when the query's SQL hasn't changed. UI thread only.
 */
open class QueryPlanPresenter {
    private data class Variable(val session: XDebugSession, val expression: String)

    /** [planSql]: the SQL the shown plan is for; null for the SQL-only page, so that visualizing again retries the plan. */
    private class PlanTab(val editor: FileEditor, val planSql: String?)

    private val tabs = HashMap<Variable, PlanTab>()

    /**
     * Switches to the variable's tab if its plan is for [sql], the query's SQL now (parameter values included).
     * Returns false when the plan has to be fetched: no tab, an SQL-only page, unknown or changed SQL.
     */
    open fun focusIfUnchanged(project: Project, session: XDebugSession, expression: String, sql: String?): Boolean {
        val tab = tabs[Variable(session, expression)] ?: return false
        val file = tab.editor.file
        if (sql == null || sql != tab.planSql || file == null) return false
        FileEditorManager.getInstance(project).openFile(file, true)
        return true
    }

    /** Shows [result] in the variable's tab, replacing what it showed. [sql] is the query's SQL it was made for. */
    open fun show(project: Project, result: VisualizeResult, expression: String, session: XDebugSession, sql: String?) {
        result.warning?.let { notify(project, it, NotificationType.WARNING) }
        val variable = Variable(session, expression)
        tabs.remove(variable)?.editor?.file?.let { FileEditorManager.getInstance(project).closeFile(it) }

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
        tabs[variable] = PlanTab(editor, if (result.kind == VisualizeResult.Kind.PLAN) sql else null)
        Disposer.register(editor) {
            Files.deleteIfExists(result.file)
            if (tabs[variable]?.editor === editor) tabs.remove(variable)
        }
    }

    open fun notify(project: Project, message: String, type: NotificationType) {
        val group = if (type == NotificationType.ERROR) "EFCore Visualizer errors" else "EFCore Visualizer"
        NotificationGroupManager.getInstance().getNotificationGroup(group)
            // The content is HTML: escape it, or "DbSet<Blog>" and "Rating < 3" in an EF error would be cut off.
            .createNotification("EFCore Visualizer", XmlStringUtil.escapeString(message).replace("\n", "<br>"), type)
            .notify(project)
    }
}
