package efcore.visualizer

import com.intellij.openapi.util.Key

/**
 * The database a plan page is for, so its tab can show that database's icon ([PlanTabIconProvider]).
 * Recognised from the page: each provider has its own template, marked by its scripts or its title.
 */
enum class PlanDatabase {
    POSTGRESQL, SQL_SERVER, SQLITE, ORACLE;

    companion object {
        /** On a plan tab's file. */
        internal val KEY = Key.create<PlanDatabase>("efcore.visualizer.planDatabase")

        /** Null for the SQL-only page (no database) and for pages from an unknown template. */
        fun of(html: String): PlanDatabase? = when {
            "pev2.umd.js" in html -> POSTGRESQL
            "qp.min.js" in html -> SQL_SERVER
            "<title>SQLite Query Plan</title>" in html -> SQLITE
            "<title>Query Execution Plan</title>" in html -> ORACLE
            else -> null
        }
    }
}
