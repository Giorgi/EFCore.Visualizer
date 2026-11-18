package efcore.visualizer

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.platform.debugger.impl.shared.proxy.XDebugSessionProxy
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XValue
import com.jetbrains.rider.debugger.IDotNetValue
import com.jetbrains.rider.debugger.shared.RiderDebuggerLinkProvider
import com.jetbrains.rider.model.debuggerWorker.ObjectPropertiesProxy
import com.jetbrains.rider.model.debuggerWorker.ValueFlags
import kotlinx.coroutines.CoroutineScope
import java.awt.event.MouseEvent

/**
 * A "Visualize Query Plan" link at the end of an EF Core query's row, in Variables, Watches, the editor's hover popup
 * and Evaluate Expression. A row shows only the first link offered, so on query rows it replaces Rider's Explore (the
 * rows are still listed under the query's Results View).
 */
class VisualizePlanRiderLinkProvider : RiderDebuggerLinkProvider {
    override suspend fun getAdditionalLink(scope: CoroutineScope, session: XDebugSessionProxy, value: XValue): XDebuggerTreeNodeHyperlink? {
        val properties = (value as? IDotNetValue)?.objectProperties as? ObjectPropertiesProxy ?: return null
        // A null query has no plan. Rider still gives it a type (the declared one, e.g. IIncludableQueryable).
        if (ValueFlags.IsNull in properties.valueFlags) return null
        if (!VisualizeQueryPlanAction.isQueryType(properties.instanceType.definitionTypeFullName)) return null
        return object : XDebuggerTreeNodeHyperlink(" Visualize Query Plan") {
            override fun getLinkTooltip() = "Show the execution plan of this EF Core query"

            override fun onClick(event: MouseEvent?) {
                // The project of the clicked view (the session proxy's getProject() is internal API).
                val clicked = event?.component?.let { DataManager.getInstance().getDataContext(it).getData(CommonDataKeys.PROJECT) }
                ApplicationManager.getApplication().invokeLater {
                    val (project, debugSession) = sessionFor(clicked) ?: return@invokeLater
                    VisualizeQueryPlanAction.instance().visualize(project, debugSession, value)
                }
            }
        }
    }

    /**
     * The .NET debug session the link belongs to: the current one of [project], or, when the click didn't say which
     * project it was in, of the open project that has one.
     */
    private fun sessionFor(project: Project?): Pair<Project, XDebugSession>? {
        val projects = project?.let { listOf(it) } ?: ProjectManager.getInstance().openProjects.toList()
        return projects.filterNot { it.isDisposed }.firstNotNullOfOrNull { candidate ->
            XDebuggerManager.getInstance(candidate).currentSession
                ?.takeIf { VisualizeQueryPlanAction.isDotNet(it) }
                ?.let { candidate to it }
        }
    }
}
