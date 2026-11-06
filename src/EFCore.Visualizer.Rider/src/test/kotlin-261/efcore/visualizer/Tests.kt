package efcore.visualizer

import com.jetbrains.rider.test.annotations.Solution
import org.testng.annotations.Test
import com.jetbrains.rider.test.annotations.TestSettings
import com.jetbrains.rider.test.enums.BuildTool
import com.jetbrains.rider.test.enums.Mono
import com.jetbrains.rider.test.enums.sdk.SdkVersion

// Rider 2025.3+ test framework: toolset is configured with @TestSettings. Use the .NET SDK found on PATH.

@TestSettings(sdkVersion = SdkVersion.AUTODETECT, buildTool = BuildTool.AUTODETECT, mono = Mono.NONE)
@Solution("SampleApp")
class DebuggeeHelperTest : DebuggeeHelperTestBase() {
    @Test fun loadsHelperAndGetsPlanFile() = runLoadsHelperAndGetsPlanFile()
}

@TestSettings(sdkVersion = SdkVersion.AUTODETECT, buildTool = BuildTool.AUTODETECT, mono = Mono.NONE)
@Solution("SampleApp")
class VisualizeActionTest : VisualizeActionTestBase() {
    @Test fun variablesContextMenuActionShowsPlanForSelectedQuery() = runVariablesContextMenuActionShowsPlanForSelectedQuery()
}

@TestSettings(sdkVersion = SdkVersion.AUTODETECT, buildTool = BuildTool.AUTODETECT, mono = Mono.NONE)
@Solution("SampleApp")
class PlanTabRenderTest : PlanTabRenderTestBase() {
    @Test fun planTabRendersWithStylesAndScript() = runPlanTabRendersWithStylesAndScript()
}
