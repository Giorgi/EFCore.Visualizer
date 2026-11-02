package efcore.visualizer

import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.intellij.xdebugger.impl.ui.XDebugSessionTab
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTree
import com.jetbrains.rider.debugger.settings.DotNetDebuggerSettings
import com.jetbrains.rider.test.debugger.disableTargetInvokeWithWatches
import com.jetbrains.rider.test.debugger.enableTargetInvokeWithWatches
import com.jetbrains.rider.test.framework.testData.IRiderTestDataMarker
import java.nio.file.Path

/** Test-framework differences between Rider versions (this file: 2026.1+). */
object RiderCompat {
    fun enableMethodCalls() = DotNetDebuggerSettings.instance.enableTargetInvokeWithWatches()
    fun disableMethodCalls() = DotNetDebuggerSettings.instance.disableTargetInvokeWithWatches()
    // getSessionTabIfInitialized() exists from 2026.2; 2026.1 only has getSessionTab().
    fun variablesTree(session: XDebugSession): XDebuggerTree? {
        val impl = session as? XDebugSessionImpl ?: return null
        val getter = listOf("getSessionTabIfInitialized", "getSessionTab")
            .firstNotNullOf { name -> runCatching { XDebugSessionImpl::class.java.getMethod(name) }.getOrNull() }
        return (getter.invoke(impl) as? XDebugSessionTab)?.variablesView?.tree
    }
}

/** Tells Rider's test framework where test solutions and gold files live (found by package lookup). */
@Suppress("unused")
object RiderTestDataMarker : IRiderTestDataMarker {
    override val testDataFromRoot: Path
        get() = Path.of("src/test/testData")
}
