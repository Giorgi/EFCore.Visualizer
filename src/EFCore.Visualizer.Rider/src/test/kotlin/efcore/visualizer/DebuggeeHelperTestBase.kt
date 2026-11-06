package efcore.visualizer

import com.jetbrains.rider.debugger.settings.DotNetDebuggerSettings
import com.jetbrains.rider.test.base.DebuggerTestBase
import com.jetbrains.rider.test.scriptingApi.evaluateExpression
import com.jetbrains.rider.test.scriptingApi.resumeSession
import com.jetbrains.rider.test.scriptingApi.toggleBreakpoint
import com.jetbrains.rider.test.scriptingApi.waitForPause
import org.testng.annotations.AfterMethod
import com.intellij.testFramework.PlatformTestUtil
import java.io.File
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Debugs an EF Core + SQLite console app in a real Rider backend, stops at a breakpoint and drives the
 * debugger-agnostic helper through Rider's own expression evaluator (func-eval):
 *   System.Reflection.Assembly.LoadFrom(@"<helper>") then IQueryableObjectSource.QueryPlanHelper.GetPlanFile(query)
 */
abstract class DebuggeeHelperTestBase : DebuggerTestBase() {
    override val projectName = "SampleApp"

    // Resolved from the installed plugin (<plugin>/netcoreapp), i.e. the packaged layout is under test too.
    private val helperDll by lazy { QueryPlanVisualizer.helperPath().toString() }
    private val timeout = Duration.ofSeconds(60)

    // Called from the version-specific @Test in DebuggeeHelperTest (Rider reads test annotations from the declaring class).
    protected fun runLoadsHelperAndGetsPlanFile() {
        val programCs = SampleApp.programCs
        val line = programCs.readLines().indexOfFirst { "// BREAKPOINT" in it } + 1
        assertTrue(line > 0, "BREAKPOINT marker not found in $programCs")

        testDebugProgram({
            // Rider tests run with method calls in watches disabled ("Implicit evaluation is disabled");
            // the visualizer needs them, exactly like Rider's default user settings allow.
            RiderCompat.enableMethodCalls()
            toggleBreakpoint(project, "SampleApp/Program.cs", line)
        }, {
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

            // Neither a plan nor SQL: both reasons are reported.
            val both = evaluationError(QueryPlanVisualizer.visualize(session, "context"))
            println("QueryPlanVisualizer.visualize(context) => $both")
            assertContains(both.message.orEmpty(), Regex("^Couldn't get the query plan: Expected an IQueryable.* Couldn't get the SQL either: Expected an IQueryable"))

            // Last, since it resumes the session: Continue during an evaluation ends it with a clear message.
            val abandoned = DebuggeeEvaluator.evaluate(session, "System.Threading.Thread.Sleep(5000)")
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            session.resume()
            val continued = evaluationError(abandoned)
            println("Continue during an evaluation => $continued")
            assertTrue(continued is NotPausedException, "unexpected: $continued")
        }, true)
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

    @AfterMethod(alwaysRun = true)
    fun restoreDebuggerSettings() {
        RiderCompat.disableMethodCalls()
    }
}
