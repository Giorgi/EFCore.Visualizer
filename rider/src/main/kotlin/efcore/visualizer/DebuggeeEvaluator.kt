package efcore.visualizer

import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import com.intellij.openapi.editor.colors.TextAttributesKey
import java.util.concurrent.CompletableFuture
import javax.swing.Icon

class EvaluationException(message: String, val expression: String) : Exception(message)

/**
 * Evaluates expressions in the paused frame through the generic IntelliJ debugger API (the same path as
 * Rider's Watches / Evaluate Expression), so no Rider backend plugin is needed.
 */
object DebuggeeEvaluator {
    const val HELPER_TYPE = "IQueryableObjectSource.QueryPlanHelper"

    fun loadHelper(session: XDebugSession, helperPath: String): CompletableFuture<String> =
        evaluate(session, "System.Reflection.Assembly.LoadFrom(${csharpString(helperPath)})")

    fun getPlanFile(session: XDebugSession, queryExpression: String): CompletableFuture<String> =
        evaluateString(session, "$HELPER_TYPE.GetPlanFile($queryExpression)")

    fun getQueryFile(session: XDebugSession, queryExpression: String): CompletableFuture<String> =
        evaluateString(session, "$HELPER_TYPE.GetQueryFile($queryExpression)")

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

    private fun evaluateRaw(session: XDebugSession, expression: String): CompletableFuture<Rendered> {
        val future = CompletableFuture<Rendered>()
        val frame = session.currentStackFrame
        val evaluator = frame?.evaluator
        if (frame == null || evaluator == null) {
            future.completeExceptionally(EvaluationException("Pause the debugger at a stack frame first.", expression))
            return future
        }
        evaluator.evaluate(expression, object : XDebuggerEvaluator.XEvaluationCallback {
            override fun evaluated(result: XValue) = result.computePresentation(ResultNode(expression, future), XValuePlace.TREE)
            override fun errorOccurred(errorMessage: String) {
                future.completeExceptionally(EvaluationException(errorMessage, expression))
            }
        }, frame.sourcePosition)
        return future
    }

    /** Captures what the debugger renders for a value, following the full-value evaluator for long strings. */
    private class ResultNode(private val expression: String, private val future: CompletableFuture<Rendered>) : XValueNode {
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
            // Strings longer than the display limit are truncated here; the full value then arrives through
            // setFullValueEvaluator, so only complete with strings that cannot have been cut off.
            if (fullValueRequested || (isString && text.length >= XValueNode.MAX_VALUE_LENGTH)) return
            complete(type, text.toString(), isString, isError)
        }

        private var type: String? = null
        private var fullValueRequested = false

        override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {
            fullValueRequested = true
            fullValueEvaluator.startEvaluation(object : XFullValueEvaluator.XFullValueEvaluationCallback {
                override fun evaluated(fullValue: String) = complete(type, fullValue, isString = true, isError = false)
                override fun errorOccurred(errorMessage: String) {
                    future.completeExceptionally(EvaluationException(errorMessage, expression))
                }
                override fun isObsolete() = future.isDone
            })
        }

        private fun complete(type: String?, value: String, isString: Boolean, isError: Boolean) {
            if (future.isDone) return
            // Rider reports an exception thrown by func-eval as a value: "Exception of type 'X' was thrown".
            if (isError || type?.endsWith("Exception") == true || value.startsWith("Exception of type '")) {
                future.completeExceptionally(EvaluationException(value, expression))
            } else {
                future.complete(Rendered(value, isString))
            }
        }
    }
}
