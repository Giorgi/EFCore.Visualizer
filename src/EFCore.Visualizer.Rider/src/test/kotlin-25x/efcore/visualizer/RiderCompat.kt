package efcore.visualizer

import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTree
import com.jetbrains.rider.debugger.settings.DotNetDebuggerSettings
import com.jetbrains.rider.test.framework.testData.IRiderTestDataMarker
import java.io.File

/** Test-framework differences between Rider versions (this file: 2025.1 - 2025.3, with kotlin-251 or kotlin-253). */
object RiderCompat {
    // The test framework's helper moved from ...test.base (2025.1) to ...test.debugger (2025.2+).
    private val settingsEx: Class<*> = listOf(
        "com.jetbrains.rider.test.debugger.DotNetDebuggerSettingsExKt",
        "com.jetbrains.rider.test.base.DotNetDebuggerSettingsExKt",
    ).firstNotNullOf { runCatching { Class.forName(it) }.getOrNull() }

    private fun call(name: String) {
        settingsEx.getMethod(name, DotNetDebuggerSettings::class.java).invoke(null, DotNetDebuggerSettings.instance)
    }

    fun enableMethodCalls() = call("enableTargetInvokeWithWatches")
    fun disableMethodCalls() = call("disableTargetInvokeWithWatches")
    fun variablesTree(session: XDebugSession): XDebuggerTree? =
        (session as? XDebugSessionImpl)?.sessionTab?.variablesView?.tree
}

/** Tells Rider's test framework where test solutions and gold files live (found by package lookup); see prepareTestData. */
@Suppress("unused")
object RiderTestDataMarker : IRiderTestDataMarker {
    override val testDataFromRoot: File
        get() = File("build/testData")
    // 2025.x resolves the default against JetBrains' own source tree; point at this project's folder directly.
    override val testData: File
        get() = File("build/testData").absoluteFile
}
