package efcore.visualizer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.Icon

/** An evaluation that failed. [message] is short enough to show to the user (the exception's own message when known). */
open class EvaluationException(message: String, val expression: String) : Exception(message)

/** The debugger isn't (or is no longer) paused, so nothing more can be evaluated: there is nothing to fall back to. */
class NotPausedException(message: String, expression: String) : EvaluationException(message, expression)

/**
 * Evaluates expressions in the paused frame through the generic IntelliJ debugger API (the same path as
 * Rider's Watches / Evaluate Expression), so no Rider backend plugin is needed.
 */
object DebuggeeEvaluator {
    const val HELPER_TYPE = "IQueryableObjectSource.QueryPlanHelper"
    private val TIMED_OUT = Regex("""^Timed out after (\d+) ms""")

    fun loadHelper(session: XDebugSession, helperPath: String): CompletableFuture<String> =
        evaluate(session, "System.Reflection.Assembly.LoadFrom(${csharpString(helperPath)})")

    fun getPlanFile(session: XDebugSession, queryExpression: String): CompletableFuture<String> =
        evaluateString(session, "$HELPER_TYPE.GetPlanFile($queryExpression)")

    fun getQueryFile(session: XDebugSession, queryExpression: String): CompletableFuture<String> =
        evaluateString(session, "$HELPER_TYPE.GetQueryFile($queryExpression)")

    fun getQueryString(session: XDebugSession, queryExpression: String): CompletableFuture<String> =
        evaluateString(session, "$HELPER_TYPE.GetQueryString($queryExpression)")

    fun csharpString(value: String) = "@\"" + value.replace("\"", "\"\"") + "\""

    /** Evaluates [expression]; completes with the rendered value, or exceptionally with [EvaluationException]. */
    fun evaluate(session: XDebugSession, expression: String): CompletableFuture<String> =
        evaluateRaw(session, expression).thenApply { it.text }

    private fun evaluateString(session: XDebugSession, expression: String): CompletableFuture<String> =
        evaluateRaw(session, expression).thenApply {
            if (!it.isString) throw EvaluationException("Expected a string but got: ${it.text}", expression)
            it.text
        }

    private class Rendered(val text: String, val isString: Boolean)

    /**
     * Starts the evaluation on the UI thread, where Rider's evaluator lives. The previous step of a chain may have
     * finished on any thread (a debugger callback, a timer, a session event).
     */
    private fun evaluateRaw(session: XDebugSession, expression: String): CompletableFuture<Rendered> {
        val future = CompletableFuture<Rendered>()
        val start = Runnable {
            try {
                startEvaluation(session, expression, future)
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) start.run() else app.invokeLater(start, ModalityState.any())
        return future
    }

    private fun startEvaluation(session: XDebugSession, expression: String, future: CompletableFuture<Rendered>) {
        val frame = session.currentStackFrame
        val evaluator = frame?.evaluator
        if (!session.isSuspended || frame == null || evaluator == null) {
            future.completeExceptionally(NotPausedException("Pause the debugger at a stack frame first.", expression))
            return
        }
        // Without this, an evaluation the debugger drops (the user pressed Continue or Stop) would never finish.
        val listener = object : XDebugSessionListener {
            override fun sessionResumed() = abandon("The debugger continued before the evaluation finished.")
            override fun sessionStopped() = abandon("The debug session ended before the evaluation finished.")
            private fun abandon(message: String) {
                future.completeExceptionally(NotPausedException(message, expression))
            }
        }
        session.addSessionListener(listener)
        future.whenComplete { _, _ -> session.removeSessionListener(listener) }

        evaluator.evaluate(expression, object : XDebuggerEvaluator.XEvaluationCallback {
            override fun evaluated(result: XValue) = result.computePresentation(ResultNode(expression, result, future), XValuePlace.TREE)
            override fun errorOccurred(errorMessage: String) {
                future.completeExceptionally(EvaluationException(errorMessage, expression))
            }
        }, frame.sourcePosition)
    }

    /** Captures what the debugger renders for a value, following the full-value evaluator for long strings. */
    private class ResultNode(
        private val expression: String,
        private val value: XValue,
        private val future: CompletableFuture<Rendered>,
    ) : XValueNode {
        override fun isObsolete() = future.isDone

        override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) =
            complete(type, value, isString = false, isError = false)

        override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
            val text = StringBuilder()
            var isString = false
            var isError = false
            presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
                override fun renderValue(value: String) { text.append(value) }
                override fun renderValue(value: String, key: TextAttributesKey) { text.append(value) }
                override fun renderStringValue(value: String) { text.append(value); isString = true }
                override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) { text.append(value); isString = true }
                override fun renderNumericValue(value: String) { text.append(value) }
                override fun renderKeywordValue(value: String) { text.append(value) }
                override fun renderComment(comment: String) {}
                override fun renderSpecialSymbol(symbol: String) { text.append(symbol) }
                override fun renderError(error: String) { text.append(error); isError = true }
            })
            type = presentation.type
            if (fullValueRequested) return
            // A string at the display limit may have been cut off. Its full value then arrives through
            // setFullValueEvaluator. If that never comes, the string was complete: use it after a short wait.
            if (isString && text.length >= XValueNode.MAX_VALUE_LENGTH) {
                val shown = text.toString()
                AppExecutorUtil.getAppScheduledExecutorService().schedule({
                    if (!fullValueRequested) complete(type, shown, isString = true, isError = false)
                }, 2, TimeUnit.SECONDS)
                return
            }
            complete(type, text.toString(), isString, isError)
        }

        @Volatile private var type: String? = null
        @Volatile private var fullValueRequested = false

        override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {
            fullValueRequested = true
            fullValueEvaluator.startEvaluation(object : XFullValueEvaluator.XFullValueEvaluationCallback {
                override fun evaluated(fullValue: String) = complete(type, fullValue, isString = true, isError = false)
                // Abstract up to 2025.x (deprecated with a default since), so it must stay for older Rider versions.
                @Suppress("OVERRIDE_DEPRECATION", "OverridingDeprecatedMember")
                override fun evaluated(fullValue: String, font: java.awt.Font?) = evaluated(fullValue)
                override fun errorOccurred(errorMessage: String) {
                    future.completeExceptionally(EvaluationException(errorMessage, expression))
                }
                override fun isObsolete() = future.isDone
            })
        }

        private fun complete(type: String?, text: String, isString: Boolean, isError: Boolean) {
            if (future.isDone) return
            // Rider's evaluation limit (see EvaluationTimeout): "Timed out after 30000 ms". English only: a localized
            // Rider's text falls through to the plain error below.
            TIMED_OUT.find(text)?.let {
                val seconds = it.groupValues[1].toLong() / 1000.0
                val limit = if (seconds % 1 == 0.0) "${seconds.toLong()} s" else "$seconds s"
                future.completeExceptionally(EvaluationException("It took longer than $limit, the debugger's time limit for evaluations.", expression))
                return
            }
            // Rider reports an exception thrown by func-eval as a value: "Exception of type 'X' was thrown", with the
            // exception object's members as children.
            if (type?.endsWith("Exception") == true || text.startsWith("Exception of type '")) {
                exceptionMessage(value).whenComplete { message, _ ->
                    future.completeExceptionally(EvaluationException(message?.takeIf { it.isNotBlank() } ?: text, expression))
                }
            } else if (isError) {
                future.completeExceptionally(EvaluationException(text, expression))
            } else {
                future.complete(Rendered(text, isString))
            }
        }
    }

    /**
     * The `Message` member of an exception value, or null when it can't be read. Rider shows a func-eval exception as
     * a value with one child, `Exception` (the exception object), so the message is one level down.
     */
    private fun exceptionMessage(exception: XValue): CompletableFuture<String?> {
        val result = CompletableFuture<String?>()
        AppExecutorUtil.getAppScheduledExecutorService().schedule({ result.complete(null) }, 10, TimeUnit.SECONDS)
        findMessage(exception, depth = 0, result)
        return result
    }

    private fun findMessage(value: XValue, depth: Int, result: CompletableFuture<String?>) {
        value.computeChildren(object : XCompositeNode {
            // Children may come in several batches: only give up after the last one if no batch had what we need.
            @Volatile private var found = false

            override fun addChildren(children: XValueChildrenList, last: Boolean) {
                if (found) return
                val names = (0 until children.size()).map(children::getName)
                val message = names.indexOf("Message")
                val exception = names.indexOf("Exception")
                when {
                    message >= 0 -> { found = true; children.getValue(message).computePresentation(MessageNode(result), XValuePlace.TREE) }
                    exception >= 0 && depth == 0 -> { found = true; findMessage(children.getValue(exception), depth + 1, result) }
                    last -> result.complete(null)
                }
            }
            // More members than one batch shows: ask for the rest (the timer in exceptionMessage covers the old API).
            @Suppress("OVERRIDE_DEPRECATION") // abstract in older Rider versions
            override fun tooManyChildren(remaining: Int) {}
            override fun tooManyChildren(remaining: Int, addNextChildren: Runnable) { if (!found) addNextChildren.run() }
            override fun setAlreadySorted(alreadySorted: Boolean) {}
            override fun setErrorMessage(errorMessage: String) { result.complete(null) }
            override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) { result.complete(null) }
            override fun setMessage(message: String, icon: Icon?, attributes: com.intellij.ui.SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {}
            override fun isObsolete() = result.isDone
        })
    }

    /** Renders a string member's value, without the quotes. */
    private class MessageNode(private val result: CompletableFuture<String?>) : XValueNode {
        override fun isObsolete() = result.isDone
        override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) { result.complete(value) }
        override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
            val text = StringBuilder()
            presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
                override fun renderValue(value: String) { text.append(value) }
                override fun renderValue(value: String, key: TextAttributesKey) { text.append(value) }
                override fun renderStringValue(value: String) { text.append(value) }
                override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) { text.append(value) }
                override fun renderNumericValue(value: String) { text.append(value) }
                override fun renderKeywordValue(value: String) { text.append(value) }
                override fun renderComment(comment: String) {}
                override fun renderSpecialSymbol(symbol: String) { text.append(symbol) }
                override fun renderError(error: String) { text.append(error) }
            })
            result.complete(text.toString())
        }
        override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}
    }
}
