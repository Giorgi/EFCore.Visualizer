package efcore.visualizer

import com.jetbrains.rider.test.annotations.Solution
import org.testng.annotations.Test
import com.jetbrains.rider.test.annotations.TestSettings
import com.jetbrains.rider.test.enums.BuildTool
import com.jetbrains.rider.test.enums.Mono
import com.jetbrains.rider.test.enums.sdk.SdkVersion

// Rider reads test annotations from the declaring class, so each test class carries its own. @TestSettings: use the
// .NET SDK found on PATH.

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
