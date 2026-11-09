package efcore.visualizer

import org.testng.annotations.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/** Recognising the database from the real templates, without Rider: a template change that breaks it fails here. */
class PlanDatabaseTest {
    private val resources: Path = Path.of(System.getProperty("user.dir"), "../EFCore.Visualizer.Core/Resources")

    @Test fun eachTemplateIsRecognised() {
        val expected = mapOf(
            "Postgres" to PlanDatabase.POSTGRESQL,
            "SqlServer" to PlanDatabase.SQL_SERVER,
            "SQLite" to PlanDatabase.SQLITE,
            "Oracle" to PlanDatabase.ORACLE,
            "Common" to null, // the SQL-only page
        )
        for ((folder, database) in expected) {
            assertEquals(database, PlanDatabase.of(Files.readString(resources.resolve("$folder/template.html"))), folder)
        }
    }
}
