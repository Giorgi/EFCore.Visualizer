package efcore.visualizer

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import java.nio.file.Files
import java.nio.file.Path

/**
 * Makes a plan page follow Rider's theme, as the VS Code extension does (webview.ts).
 *
 * The templates switch to dark mode only through prefers-color-scheme (a <link media>, @media rules and, for
 * Bootstrap, matchMedia), and Rider's embedded browser always reports light. So each
 * "(prefers-color-scheme: dark|light)" in those three places becomes "all" when it matches Rider's theme and
 * "not all" when it doesn't, and the templates' white/black backgrounds become the editor's colors.
 */
internal object PageTheme {
    private val PREFERS_SCHEME =
        Regex("""(media\s*=\s*"|@media\s+|matchMedia\(\s*["'])\(prefers-color-scheme:\s*(dark|light)\)""", RegexOption.IGNORE_CASE)
    private val HEAD_END = Regex("</head>", RegexOption.IGNORE_CASE)

    /** Rewrites the page at [file] for the current theme. */
    fun applyTo(file: Path) {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val html = Files.readString(file)
        Files.writeString(file, apply(html, !JBColor.isBright(), ColorUtil.toHtmlColor(scheme.defaultBackground), ColorUtil.toHtmlColor(scheme.defaultForeground)))
    }

    /** [background] and [foreground]: CSS colors, the editor's. */
    fun apply(html: String, dark: Boolean, background: String, foreground: String): String {
        val withScheme = html.replace(PREFERS_SCHEME) { match ->
            val matches = match.groupValues[2].equals("dark", ignoreCase = true) == dark
            match.groupValues[1] + if (matches) "all" else "not all"
        }
        val colors = "<style>:root{--bg-color:$background;--text-color:$foreground;}</style>"
        return if (HEAD_END.containsMatchIn(withScheme)) withScheme.replaceFirst(HEAD_END, "$colors</head>") else withScheme + colors
    }
}
