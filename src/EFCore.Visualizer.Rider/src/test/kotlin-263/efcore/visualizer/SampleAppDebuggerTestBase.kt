package efcore.visualizer

import com.jetbrains.rider.test.junit5.base.debugger.DebuggerTestBase
import com.jetbrains.rider.test.scriptingApi.toggleBreakpoint
import org.junit.jupiter.api.AfterEach
import kotlin.test.assertTrue

/** A test that debugs the sample app (the SampleApp test solution) and stops at its `// BREAKPOINT` line. */
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
