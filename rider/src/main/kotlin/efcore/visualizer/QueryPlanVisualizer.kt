package efcore.visualizer

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.xdebugger.XDebugSession
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/** What the visualizer produced: a plan page, or the SQL page when the plan could not be extracted. */
data class VisualizeResult(val kind: Kind, val file: Path, val warning: String? = null) {
    enum class Kind { PLAN, QUERY }
}

object QueryPlanVisualizer {
    private const val PLUGIN_ID = "efcore.visualizer"
    const val HELPER_PROPERTY = "efcore.visualizer.helper"

    /** <plugin>/netcoreapp/EFCore.Visualizer.DebuggeeHelper.dll; templates live in <plugin>/Resources. */
    fun helperPath(): Path {
        System.getProperty(HELPER_PROPERTY)?.let { return Path.of(it) }
        val plugin = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID)) ?: error("Plugin $PLUGIN_ID is not loaded")
        return plugin.pluginPath.resolve("netcoreapp/EFCore.Visualizer.DebuggeeHelper.dll")
    }

    /**
     * Loads the helper into the debuggee and asks it for the plan of [queryExpression]. Like the Visual Studio
     * visualizer, falls back to the SQL page (with a warning) when the plan cannot be produced.
     */
    fun visualize(session: XDebugSession, queryExpression: String): CompletableFuture<VisualizeResult> =
        DebuggeeEvaluator.loadHelper(session, helperPath().toString())
            .thenCompose { DebuggeeEvaluator.getPlanFile(session, queryExpression) }
            .thenApply { VisualizeResult(VisualizeResult.Kind.PLAN, Path.of(it)) }
            .exceptionallyCompose { planError ->
                val cause = planError.unwrap()
                // If the helper itself could not be loaded there is nothing to fall back to.
                if (cause is EvaluationException && cause.expression.startsWith("System.Reflection.Assembly.LoadFrom"))
                    CompletableFuture.failedFuture(cause)
                else DebuggeeEvaluator.getQueryFile(session, queryExpression)
                    .thenApply { VisualizeResult(VisualizeResult.Kind.QUERY, Path.of(it), cause.message) }
            }

    fun Throwable.unwrap(): Throwable =
        if ((this is java.util.concurrent.CompletionException || this is java.util.concurrent.ExecutionException) && cause != null) cause!!.unwrap() else this
}
