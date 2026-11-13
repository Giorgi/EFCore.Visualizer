package efcore.visualizer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.xdebugger.XDebugSession
import com.jetbrains.rd.util.reactive.IOptProperty
import com.jetbrains.rider.model.debuggerWorker.DotNetDebuggerSessionModel
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Rider stops an evaluation after 1 s by default (Settings | Debugger | .NET: evaluation timeout). Getting a plan can
 * take longer, since some databases run the query to get it. While the visualizer evaluates, it raises the limit of
 * that debug session (not the saved setting) to [LIMIT_MS] unless it is already higher, and restores it after.
 *
 * The limit belongs to the session, not to single evaluations: while a visualization runs, the user's own Watches and
 * Variables in that session get the raised limit too.
 */
object EvaluationTimeout {
    const val LIMIT_MS = 30_000

    /**
     * Longest a whole visualization may take before it is given up: the helper, the plan and the SQL fallback, each up
     * to the session's limit, plus time for error lookups. So the user's own (higher) limit is never cut short.
     */
    private fun watchdogMs(limitMs: Int) = 3L * limitMs + 15_000

    private val log = logger<EvaluationTimeout>()
    @Volatile private var reportedUnavailable = false

    /** Visualizations running per session, and the limit to restore when the last one ends. UI thread only. */
    private class Raised(val original: Int, var users: Int)
    private val raised = WeakHashMap<XDebugSession, Raised>()

    /**
     * Runs [evaluation] with the session's limit raised to at least [LIMIT_MS], and restores the limit when it ends,
     * however it ends. Doesn't block: the limit is changed on the UI thread, then [evaluation] starts.
     */
    fun <T> withRaisedLimit(session: XDebugSession, evaluation: () -> CompletableFuture<T>): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        onUiThread {
            val userLimit = currentLimit(session) ?: LIMIT_MS
            val limit = raise(session)
            result.whenComplete { _, _ -> if (limit != null) onUiThread { restore(session, limit) } }
            // So a debugger that never answers can't leave the user waiting, or the limit raised.
            val watchdog = watchdogMs(maxOf(userLimit, LIMIT_MS))
            AppExecutorUtil.getAppScheduledExecutorService().schedule({
                result.completeExceptionally(EvaluationException("The debugger didn't answer within ${watchdog / 1000} s.", ""))
            }, watchdog, TimeUnit.MILLISECONDS)
            try {
                evaluation().whenComplete { value, error -> if (error != null) result.completeExceptionally(error) else result.complete(value) }
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return result
    }

    /** The session's current evaluation limit, or null when this Rider version doesn't expose it. UI thread only. */
    fun currentLimit(session: XDebugSession): Int? = limitProperty(session)?.valueOrNull

    /** Sets the session's evaluation limit, as the user's setting would. For tests. UI thread only. */
    internal fun setLimit(session: XDebugSession, ms: Int) {
        checkNotNull(limitProperty(session)) { "this Rider version doesn't expose the evaluation limit" }.set(ms)
    }

    private fun raise(session: XDebugSession): IOptProperty<Int>? {
        val limit = limitProperty(session) ?: return null
        raised[session]?.let { it.users++; return limit }
        val original = limit.valueOrNull ?: return null
        if (original >= LIMIT_MS) return null
        raised[session] = Raised(original, users = 1)
        limit.set(LIMIT_MS)
        log.info("Raised the evaluation limit of this debug session from $original ms to $LIMIT_MS ms")
        return limit
    }

    private fun restore(session: XDebugSession, limit: IOptProperty<Int>) {
        val state = raised[session] ?: return
        if (--state.users > 0) return
        raised.remove(session)
        // Leave it alone if it changed meanwhile: the user changing the setting updates the session's limit too. (A
        // user who sets exactly LIMIT_MS during a visualization gets their old value back; unlikely, and harmless.)
        if (limit.valueOrNull == LIMIT_MS) runCatching { limit.set(state.original) }.onFailure { log.info("Couldn't restore the evaluation limit", it) }
    }

    /**
     * SessionProperties.evaluationTimeout of the session's DotNetDebugProcess, which Rider keeps in step with the
     * setting. DotNetDebugProcess isn't on the plugin's compile classpath, hence reflection; when Rider changes it,
     * the visualizer keeps the user's limit.
     */
    private fun limitProperty(session: XDebugSession): IOptProperty<Int>? = runCatching {
        val process = session.debugProcess
        val model = process.javaClass.getMethod("getSessionProxy").invoke(process) as DotNetDebuggerSessionModel
        model.sessionProperties.evaluationTimeout
    }.onFailure {
        if (!reportedUnavailable) {
            reportedUnavailable = true
            log.info("Can't read the debug session's evaluation limit; keeping the user's", it)
        }
    }.getOrNull()

    private fun onUiThread(block: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) block() else app.invokeLater(block, ModalityState.any())
    }
}
