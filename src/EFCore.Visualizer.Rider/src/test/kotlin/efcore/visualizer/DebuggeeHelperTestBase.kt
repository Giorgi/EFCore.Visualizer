package efcore.visualizer

import com.jetbrains.rider.debugger.settings.DotNetDebuggerSettings
import com.jetbrains.rider.test.scriptingApi.evaluateExpression
import com.jetbrains.rider.test.scriptingApi.resumeSession
import com.jetbrains.rider.test.scriptingApi.waitForPause
import com.intellij.testFramework.PlatformTestUtil
import java.io.File
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Debugs an EF Core + SQLite console app in a real Rider backend, stops at a breakpoint and drives the
 * debugger-agnostic helper through Rider's own expression evaluator (func-eval):
 *   System.Reflection.Assembly.LoadFrom(@"<helper>") then IQueryableObjectSource.QueryPlanHelper.GetPlanFile(query)
 */
abstract class DebuggeeHelperTestBase : SampleAppDebuggerTestBase() {

    // Resolved from the installed plugin (<plugin>/netcoreapp), i.e. the packaged layout is under test too.
    private val helperDll by lazy { QueryPlanVisualizer.helperPath().toString() }
    private val timeout = Duration.ofSeconds(60)

    // Called from the version-specific @Test in DebuggeeHelperTest (Rider reads test annotations from the declaring class).
    protected fun runLoadsHelperAndGetsPlanFile() {
        testDebugProgram({ stopAtBreakpoint() }, {
            waitForPause()

            val loaded = evaluateExpression("System.Reflection.Assembly.LoadFrom(@\"$helperDll\")", timeout)
            println("Assembly.LoadFrom => ${loaded.result}")
            assertContains(loaded.result, "EFCore.Visualizer.Core")

            val queryFile = evaluateExpression("IQueryableObjectSource.QueryPlanHelper.GetQueryFile(query)", timeout)
            println("GetQueryFile(query) => ${queryFile.result}")

            val planFile = evaluateExpression("IQueryableObjectSource.QueryPlanHelper.GetPlanFile(query)", timeout)
            println("GetPlanFile(query) => ${planFile.result}")
            val queryPath = File(stringValue(queryFile.result))
            assertTrue(queryPath.isFile, "Query file does not exist: $queryPath")
            assertContains(queryPath.readText(), "FROM &quot;Blogs&quot;")

            val planPath = File(stringValue(planFile.result))
            assertTrue(planPath.isFile, "Plan file does not exist: $planPath")
            assertContains(planPath.readText(), Regex("SEARCH \\w+ USING INDEX IX_Blogs_Rating"))

            // Rider reports an exception thrown by func-eval as the result text, not as a failed evaluation.
            val failed = evaluateExpression("IQueryableObjectSource.QueryPlanHelper.GetPlanFile(context)", timeout)
            println("GetPlanFile(context) => ${failed.result}")
            assertContains(failed.result, "System.ArgumentException")

            queryPath.delete()
            planPath.delete()

            // The plugin's own path: XDebuggerEvaluator + presentation rendering, as used by the menu action.
            val result = PlatformTestUtil.waitForFuture(QueryPlanVisualizer.visualize(session, "query"), 60_000)
            println("QueryPlanVisualizer.visualize(query) => $result")
            assertEquals(VisualizeResult.Kind.PLAN, result.kind)
            assertContains(result.file.readText(), Regex("SEARCH \\w+ USING INDEX IX_Blogs_Rating"))
            result.file.deleteIfExists()

            // The user sees the exception's own message, not just its type.
            val error = evaluationError(DebuggeeEvaluator.getPlanFile(session, "context"))
            println("DebuggeeEvaluator.getPlanFile(context) => $error")
            assertContains(error.message.orEmpty(), "Expected an IQueryable but got")

            // No plan (the table doesn't exist), but SQL: the SQL page, with the reason as a warning.
            val fallback = PlatformTestUtil.waitForFuture(QueryPlanVisualizer.visualize(session, "broken"), 60_000)
            println("QueryPlanVisualizer.visualize(broken) => $fallback")
            assertEquals(VisualizeResult.Kind.QUERY, fallback.kind)
            assertContains(fallback.warning.orEmpty(), "MissingTable")
            assertContains(fallback.file.readText(), "MissingTable")
            fallback.file.deleteIfExists()

            // Neither a plan nor SQL: both reasons are reported.
            val both = evaluationError(QueryPlanVisualizer.visualize(session, "context"))
            println("QueryPlanVisualizer.visualize(context) => $both")
            assertContains(both.message.orEmpty(), Regex("^Couldn't get the query plan: Expected an IQueryable.* Couldn't get the SQL either: Expected an IQueryable"))

            checkEvaluationLimit()

            // Last, since it resumes the session: Continue during an evaluation ends it with a clear message.
            val abandoned = EvaluationTimeout.withRaisedLimit(session) { DebuggeeEvaluator.evaluate(session, "System.Threading.Thread.Sleep(5000)") }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            session.resume()
            val continued = evaluationError(abandoned)
            println("Continue during an evaluation => $continued")
            assertTrue(continued is NotPausedException, "unexpected: $continued")
        }, true)
    }

    /** Rider's default limit is 1 s; the visualizer raises it for its own evaluations only (EvaluationTimeout). */
    private fun com.jetbrains.rider.test.scriptingApi.DebugTestExecutionContext.checkEvaluationLimit() {
        val testLimit = assertNotNull(EvaluationTimeout.currentLimit(session), "this Rider version doesn't expose the evaluation limit")
        fun limitAfterwards(): Int? { PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); return EvaluationTimeout.currentLimit(session) }
        val slow = "System.Threading.Thread.Sleep(3000)"
        try {
            EvaluationTimeout.setLimit(session, 1000)

            // The visualizer's own path restores the limit, whether it succeeds or fails.
            PlatformTestUtil.waitForFuture(QueryPlanVisualizer.visualize(session, "query"), 60_000).file.deleteIfExists()
            assertEquals(1000, limitAfterwards(), "not restored after a plan")
            evaluationError(QueryPlanVisualizer.visualize(session, "context"))
            assertEquals(1000, limitAfterwards(), "not restored after a failure")

            val raised = PlatformTestUtil.waitForFuture(EvaluationTimeout.withRaisedLimit(session) { DebuggeeEvaluator.evaluate(session, slow) }, 60_000)
            println("$slow with the raised limit => $raised")
            assertEquals(1000, limitAfterwards(), "not restored after a slow evaluation")

            val thrown = runCatching { PlatformTestUtil.waitForFuture(EvaluationTimeout.withRaisedLimit<String>(session) { error("boom") }, 60_000) }
            assertTrue(thrown.isFailure, "a throwing evaluation succeeded")
            assertEquals(1000, limitAfterwards(), "not restored after a synchronous throw")

            // Overlapping visualizations: raised until the last one ends.
            val first = EvaluationTimeout.withRaisedLimit(session) { DebuggeeEvaluator.evaluate(session, "System.Threading.Thread.Sleep(1500)") }
            val second = EvaluationTimeout.withRaisedLimit(session) { DebuggeeEvaluator.evaluate(session, "System.Threading.Thread.Sleep(1500)") }
            assertEquals(EvaluationTimeout.LIMIT_MS, EvaluationTimeout.currentLimit(session), "not raised while visualizations run")
            PlatformTestUtil.waitForFuture(first, 60_000)
            if (!second.isDone) assertEquals(EvaluationTimeout.LIMIT_MS, limitAfterwards(), "restored while another visualization still runs")
            PlatformTestUtil.waitForFuture(second, 60_000)
            assertEquals(1000, limitAfterwards(), "not restored after overlapping visualizations")

            // A limit already above 30 s stays as it is.
            EvaluationTimeout.setLimit(session, 60_000)
            var during: Int? = null
            PlatformTestUtil.waitForFuture(EvaluationTimeout.withRaisedLimit(session) {
                during = EvaluationTimeout.currentLimit(session)
                DebuggeeEvaluator.evaluate(session, "1")
            }, 60_000)
            assertEquals(60_000, during)
            assertEquals(60_000, limitAfterwards())

            // Last: a timed-out evaluation leaves an aborted func-eval behind.
            EvaluationTimeout.setLimit(session, 1000)
            val timedOut = evaluationError(DebuggeeEvaluator.evaluate(session, slow))
            println("$slow at 1000 ms => $timedOut")
            assertContains(timedOut.message.orEmpty(), "took longer than 1 s")
        } finally {
            EvaluationTimeout.setLimit(session, testLimit)
        }
    }

    /** The EvaluationException [future] failed with (waitForFuture wraps it: AssertionError -> ExecutionException -> cause). */
    private fun evaluationError(future: java.util.concurrent.CompletableFuture<*>): EvaluationException {
        val error = runCatching { PlatformTestUtil.waitForFuture(future, 60_000) }.exceptionOrNull()
        val evaluation = error?.let { e -> generateSequence(e) { it.cause }.firstOrNull { it is EvaluationException } }
        assertTrue(evaluation is EvaluationException, "Expected an EvaluationException but got: $error")
        return evaluation
    }

    /** Rider presents strings as `{string} "value"`. */
    private fun stringValue(presentation: String): String {
        val match = Regex("""^\{string\} "(.*)"$""", RegexOption.DOT_MATCHES_ALL).find(presentation.trim())
        assertTrue(match != null, "Expected a string result but got: $presentation")
        return match.groupValues[1]
    }
}
