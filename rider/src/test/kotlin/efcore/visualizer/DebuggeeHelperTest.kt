package efcore.visualizer

import com.jetbrains.rider.debugger.settings.DotNetDebuggerSettings
import com.jetbrains.rider.test.annotations.Solution
import com.jetbrains.rider.test.annotations.TestSettings
import com.jetbrains.rider.test.enums.BuildTool
import com.jetbrains.rider.test.enums.Mono
import com.jetbrains.rider.test.enums.sdk.SdkVersion
import com.jetbrains.rider.test.base.DebuggerTestBase
import com.jetbrains.rider.test.debugger.disableTargetInvokeWithWatches
import com.jetbrains.rider.test.debugger.enableTargetInvokeWithWatches
import com.jetbrains.rider.test.scriptingApi.evaluateExpression
import com.jetbrains.rider.test.scriptingApi.resumeSession
import com.jetbrains.rider.test.scriptingApi.toggleBreakpoint
import com.jetbrains.rider.test.scriptingApi.waitForPause
import org.testng.annotations.AfterMethod
import org.testng.annotations.Test
import java.io.File
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Debugs an EF Core + SQLite console app in a real Rider backend, stops at a breakpoint and drives the
 * debugger-agnostic helper through Rider's own expression evaluator (func-eval):
 *   System.Reflection.Assembly.LoadFrom(@"<helper>") then IQueryableObjectSource.QueryPlanHelper.GetPlanFile(query)
 */
// Use the .NET SDK found on PATH instead of having the framework download toolsets.
@TestSettings(sdkVersion = SdkVersion.AUTODETECT, buildTool = BuildTool.AUTODETECT, mono = Mono.NONE)
@Solution("SampleApp")
class DebuggeeHelperTest : DebuggerTestBase() {
    override val projectName = "SampleApp"

    private val helperDll = File(System.getProperty("efcore.visualizer.helper")).absolutePath
    private val timeout = Duration.ofSeconds(60)

    @Test
    fun loadsHelperAndGetsPlanFile() {
        val programCs = activeSolutionDirectory.resolve("SampleApp/Program.cs").toFile()
        val line = programCs.readLines().indexOfFirst { "// BREAKPOINT" in it } + 1
        assertTrue(line > 0, "BREAKPOINT marker not found in $programCs")

        testDebugProgram({
            // Rider tests run with method calls in watches disabled ("Implicit evaluation is disabled");
            // the visualizer needs them, exactly like Rider's default user settings allow.
            DotNetDebuggerSettings.instance.enableTargetInvokeWithWatches()
            toggleBreakpoint(project, "SampleApp/Program.cs", line)
        }, {
            waitForPause()

            val loaded = evaluateExpression("System.Reflection.Assembly.LoadFrom(@\"$helperDll\")", timeout)
            println("Assembly.LoadFrom => ${loaded.result}")
            assertContains(loaded.result, "EFCore.Visualizer.DebuggeeHelper")

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

            resumeSession()
        }, true)
    }

    /** Rider presents strings as `{string} "value"`. */
    private fun stringValue(presentation: String): String {
        val match = Regex("""^\{string\} "(.*)"$""", RegexOption.DOT_MATCHES_ALL).find(presentation.trim())
        assertTrue(match != null, "Expected a string result but got: $presentation")
        return match.groupValues[1]
    }

    @AfterMethod(alwaysRun = true)
    fun restoreDebuggerSettings() {
        DotNetDebuggerSettings.instance.disableTargetInvokeWithWatches()
    }
}
