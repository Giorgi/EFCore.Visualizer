package efcore.visualizer

import org.testng.annotations.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The page rewriting for Rider's theme, without Rider, on the real templates (the same as VS Code's). */
class PageThemeTest {
    private val resources: Path = Path.of(System.getProperty("user.dir"), "../EFCore.Visualizer.Core/Resources")

    private fun template(folder: String) = Files.readString(resources.resolve("$folder/template.html"))

    @Test fun darkThemeTurnsOnTheDarkRulesAndOffTheLightOnes() {
        val page = """<link media="(prefers-color-scheme: light)" href="a.css"><link media="(prefers-color-scheme: dark)" href="b.css">""" +
            """<style>@media (prefers-color-scheme: dark) { x {} }</style><script>matchMedia("(prefers-color-scheme: dark)")</script></head>"""
        assertEquals(
            """<link media="not all" href="a.css"><link media="all" href="b.css"><style>@media all { x {} }</style>""" +
                """<script>matchMedia("all")</script><style>:root{--bg-color:#1e1f22;--text-color:#bcbec4;}</style></head>""",
            PageTheme.apply(page, dark = true, background = "#1e1f22", foreground = "#bcbec4"))
    }

    @Test fun lightThemeDoesTheOpposite() {
        assertEquals("""<link media="all"><link media="not all">""" + "<style>:root{--bg-color:#fff;--text-color:#000;}</style>",
            PageTheme.apply("""<link media="(prefers-color-scheme: light)"><link media="(prefers-color-scheme: dark)">""", dark = false, background = "#fff", foreground = "#000"))
    }

    @Test fun everyTemplateIsRewritten() {
        for (folder in listOf("Common", "SQLite", "SqlServer", "Postgres", "Oracle")) {
            val page = PageTheme.apply(template(folder), dark = true, background = "#1e1f22", foreground = "#bcbec4")
            assertFalse(Regex("""\(prefers-color-scheme""").containsMatchIn(page), "$folder: a prefers-color-scheme was left")
            assertContains(page, "--bg-color:#1e1f22", message = "$folder: the editor colors are missing")
        }
    }
}
