package efcore.visualizer

import com.intellij.icons.AllIcons
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * A plan tab shows its database's icon instead of the HTML editor's globe. Rider's own database icons (the ones its
 * Database tool window uses), so they follow the theme. Other files: null, so the next provider decides.
 */
class PlanTabIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? = when (file.getUserData(PlanDatabase.KEY)) {
        PlanDatabase.POSTGRESQL -> AllIcons.Providers.Postgresql
        PlanDatabase.SQL_SERVER -> AllIcons.Providers.SqlServer
        PlanDatabase.SQLITE -> AllIcons.Providers.Sqlite
        PlanDatabase.ORACLE -> AllIcons.Providers.Oracle
        null -> null
    }
}
