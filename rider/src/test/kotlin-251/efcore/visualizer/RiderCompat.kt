package efcore.visualizer

import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTree
import com.jetbrains.rider.debugger.settings.DotNetDebuggerSettings
import com.jetbrains.rider.test.base.disableTargetInvokeWithWatches
import com.jetbrains.rider.test.base.enableTargetInvokeWithWatches
import com.jetbrains.rider.test.framework.testData.IRiderTestDataMarker
import java.io.File

/** Test-framework differences between Rider versions (this file: 2025.x). */
object RiderCompat {
    fun enableMethodCalls() = DotNetDebuggerSettings.instance.enableTargetInvokeWithWatches()
    fun disableMethodCalls() = DotNetDebuggerSettings.instance.disableTargetInvokeWithWatches()
    fun variablesTree(session: XDebugSession): XDebuggerTree? =
        (session as? XDebugSessionImpl)?.sessionTab?.variablesView?.tree
}

/** Tells Rider's test framework where test solutions and gold files live (found by package lookup). */
@Suppress("unused")
object RiderTestDataMarker : IRiderTestDataMarker {
    override val testDataFromRoot: File
        get() = File("src/test/testData")
    // 2025.x resolves the default against JetBrains' own source tree; point at this project's folder directly.
    override val testData: File
        get() = File("src/test/testData").absoluteFile
}
