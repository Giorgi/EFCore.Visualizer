package efcore.visualizer

import com.jetbrains.rider.test.junit5.base.debugger.DebuggerTestBase
import com.jetbrains.rider.test.scriptingApi.toggleBreakpoint
import com.jetbrains.rider.test.shared.constants.TeamCityTags
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import kotlin.test.assertTrue

/**
 * A test that debugs the sample app (the SampleApp test solution) and stops at its `// BREAKPOINT` line.
 *
 * Rider's JUnit 5 tests must carry a "season" or "episode" tag (JetBrains' own CI uses them to pick tests);
 * LocalOnly is the one it always accepts. Inherited by the test classes.
 */
@Tag(TeamCityTags.LocalOnly)
abstract class SampleAppDebuggerTestBase : DebuggerTestBase() {
    override val projectName = "SampleApp"

    /** For testDebugProgram's first step: allows method calls in watches and sets the breakpoint. */
    protected fun stopAtBreakpoint() {
        val line = SampleApp.programCs.readLines().indexOfFirst { "// BREAKPOINT" in it } + 1
        assertTrue(line > 0, "BREAKPOINT marker not found in ${SampleApp.programCs}")
        // Rider tests run with method calls in watches disabled ("Implicit evaluation is disabled"); the visualizer
        // needs them, as Rider's default user settings allow.
        RiderCompat.enableMethodCalls()
        toggleBreakpoint(project, "SampleApp/Program.cs", line)
    }

    @AfterEach
    fun restoreDebuggerSettings() {
        RiderCompat.disableMethodCalls()
    }
}
