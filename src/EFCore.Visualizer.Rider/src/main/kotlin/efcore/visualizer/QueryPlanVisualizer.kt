package efcore.visualizer

import com.intellij.openapi.application.PathManager
import com.intellij.xdebugger.XDebugSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/** What the visualizer produced: a plan page, or the SQL page when the plan could not be extracted. */
data class VisualizeResult(val kind: Kind, val file: Path, val warning: String? = null) {
    enum class Kind { PLAN, QUERY }
}

object QueryPlanVisualizer {
    /** <plugin>/netcoreapp/EFCore.Visualizer.Core.dll; templates live in <plugin>/Resources. */
    fun helperPath(): Path {
        // <plugin>/lib/<our jar>  ->  <plugin>   (the plugin-descriptor lookups are internal API in 2026.2)
        val jar = PathManager.getJarPathForClass(QueryPlanVisualizer::class.java)
            ?: throw IllegalStateException("Can't find the EFCore Visualizer plugin's files. Reinstall the plugin.")
        return Path.of(jar).parent.parent.resolve("netcoreapp/EFCore.Visualizer.Core.dll")
    }

    /** The helper to load into the app: a per-version copy of the plugin's (see HelperCopy). */
    private fun helperToLoad(): Path {
        val helper = helperPath()
        check(Files.isRegularFile(helper)) { "The EFCore Visualizer plugin is missing $helper. Reinstall the plugin." }
        return HelperCopy.of(helper)
    }

    /** Shown with load failures and missing pages: the helper and the pages are files on the IDE's machine. */
    const val REMOTE_HINT = "Debugging an app in Docker, WSL or on another machine isn't supported yet."

    /**
     * Loads the helper into the debuggee and asks it for the plan of [queryExpression]. Like the Visual Studio
     * visualizer, falls back to the SQL page (with a warning) when the plan cannot be produced.
     */
    fun visualize(session: XDebugSession, queryExpression: String): CompletableFuture<VisualizeResult> {
        val helper = runCatching { helperToLoad() }.getOrElse { return CompletableFuture.failedFuture(it) }
        // Set when the visualization was given up (EvaluationTimeout's watchdog): a page that arrives later is deleted.
        val visualization = java.util.concurrent.atomic.AtomicReference<CompletableFuture<VisualizeResult>>()
        return EvaluationTimeout.withRaisedLimit(session) {
            // Nothing to fall back to when the helper doesn't load: report that on its own.
            DebuggeeEvaluator.loadHelper(session, helper.toString())
                .exceptionally {
                    val cause = it.unwrap()
                    // "The debugger continued ..." isn't a load failure: report it as it is.
                    if (cause is NotPausedException) throw cause
                    // An app that runs elsewhere can't open the helper's path on this machine.
                    val missing = Regex("Could not load file|FileNotFound|DirectoryNotFound|Could not find").containsMatchIn(cause.message.orEmpty())
                    throw EvaluationException("Couldn't load the visualizer into the app: ${cause.message}" + (if (missing) " $REMOTE_HINT" else ""), "")
                }
                .thenCompose { getPlanOrQuery(session, queryExpression) }
                .whenComplete { result, _ -> if (result != null && visualization.get()?.isCompletedExceptionally == true) Files.deleteIfExists(result.file) }
        }.also(visualization::set)
    }

    /**
     * The SQL of [queryExpression] now, parameter values included, or null when it can't be had. Doesn't run the
     * query: this tells whether a plan already shown for the variable is still up to date.
     */
    fun queryString(session: XDebugSession, queryExpression: String): CompletableFuture<String?> {
        val helper = runCatching { helperToLoad() }.getOrElse { return CompletableFuture.completedFuture(null) }
        return EvaluationTimeout.withRaisedLimit(session) {
            DebuggeeEvaluator.loadHelper(session, helper.toString()).thenCompose { DebuggeeEvaluator.getQueryString(session, queryExpression) }
        }.handle { sql, _ -> sql }
    }

    private fun getPlanOrQuery(session: XDebugSession, queryExpression: String): CompletableFuture<VisualizeResult> =
        DebuggeeEvaluator.getPlanFile(session, queryExpression)
            .thenApply { VisualizeResult(VisualizeResult.Kind.PLAN, Path.of(it)) }
            .exceptionallyCompose { planFailure ->
                val cause = planFailure.unwrap()
                // Not paused any more: the SQL can't be fetched either, so just say what happened.
                if (cause is NotPausedException) return@exceptionallyCompose CompletableFuture.failedFuture(cause)
                val planError = cause.message.orEmpty()
                DebuggeeEvaluator.getQueryFile(session, queryExpression)
                    .thenApply { VisualizeResult(VisualizeResult.Kind.QUERY, Path.of(it), planError.takeIf { it.isNotBlank() }) }
                    .exceptionally { queryFailure ->
                        throw EvaluationException(
                            "Couldn't get the query plan: ${sentence(planError)} Couldn't get the SQL either: ${sentence(queryFailure.unwrap().message.orEmpty())}",
                            queryExpression,
                        )
                    }
            }

    /** Ends [text] with a period unless it already ends a sentence, so two messages can be joined. */
    private fun sentence(text: String) = if (text.endsWith('.') || text.endsWith('!') || text.endsWith('?')) text else "$text."

    fun Throwable.unwrap(): Throwable =
        if ((this is java.util.concurrent.CompletionException || this is java.util.concurrent.ExecutionException) && cause != null) cause!!.unwrap() else this
}
