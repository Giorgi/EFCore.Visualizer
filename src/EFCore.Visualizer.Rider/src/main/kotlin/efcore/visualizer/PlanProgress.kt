package efcore.visualizer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A progress indicator with the elapsed time while a plan is fetched, as in the VS Code extension. SQL Server,
 * PostgreSQL and Oracle run the query to get the actual plan, so without it nothing would show for as long as the
 * query takes.
 *
 * There is no Cancel button: the work is an evaluation inside the paused app, which the debugger can't interrupt.
 */
object PlanProgress {
    /** Wait this long before showing anything, so fast plans (SQLite) don't flash an indicator. */
    const val DELAY_MS = 500L

    /** From this many seconds on, the indicator also says why it can take a while. */
    const val HINT_AFTER_S = 3L

    const val HINT = "SQL Server, PostgreSQL and Oracle run the query to get the actual plan, so this takes as long as the query."

    fun message(expression: String, seconds: Long): String {
        val text = "Getting the query plan of $expression"
        if (seconds < 1) return "$text…"
        return "$text ($seconds s)" + if (seconds >= HINT_AFTER_S) ". $HINT" else ""
    }

    /** Shows the indicator while [work] runs, once it has taken longer than [DELAY_MS]. Returns [work]. */
    fun <T> whileRunning(project: Project, expression: String, work: CompletableFuture<T>): CompletableFuture<T> {
        val app = ApplicationManager.getApplication()
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (work.isDone) return@schedule
            app.invokeLater {
                if (work.isDone || project.isDisposed) return@invokeLater
                ProgressManager.getInstance().run(object : Task.Backgroundable(project, "EFCore Visualizer", false) {
                    override fun run(indicator: ProgressIndicator) {
                        // A test environment may run this on the UI thread, where waiting would hold up the evaluation.
                        if (app.isDispatchThread) return
                        indicator.isIndeterminate = true
                        val start = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(DELAY_MS)
                        while (!work.isDone) {
                            indicator.text = message(expression, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start))
                            runCatching { work.get(1, TimeUnit.SECONDS) }
                        }
                    }
                })
            }
        }, DELAY_MS, TimeUnit.MILLISECONDS)
        return work
    }
}
