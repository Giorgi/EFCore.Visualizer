package efcore.visualizer

import com.jetbrains.rider.test.annotations.Solution
import org.testng.annotations.Test
import com.jetbrains.rider.test.annotations.TestEnvironment
import com.jetbrains.rider.test.env.enums.BuildTool
import com.jetbrains.rider.test.env.enums.SdkVersion

// Rider 2025.1 - 2025.2 test framework: toolset is configured with @TestEnvironment. Use the .NET SDK found on PATH.

@TestEnvironment(sdkVersion = SdkVersion.AUTODETECT, buildTool = BuildTool.AUTODETECT)
@Solution("SampleApp")
class DebuggeeHelperTest : DebuggeeHelperTestBase() {
    @Test fun loadsHelperAndGetsPlanFile() = runLoadsHelperAndGetsPlanFile()
}

@TestEnvironment(sdkVersion = SdkVersion.AUTODETECT, buildTool = BuildTool.AUTODETECT)
@Solution("SampleApp")
class VisualizeActionTest : VisualizeActionTestBase() {
    @Test fun variablesContextMenuActionShowsPlanForSelectedQuery() = runVariablesContextMenuActionShowsPlanForSelectedQuery()
}

@TestEnvironment(sdkVersion = SdkVersion.AUTODETECT, buildTool = BuildTool.AUTODETECT)
@Solution("SampleApp")
class PlanTabRenderTest : PlanTabRenderTestBase() {
    @Test fun planTabRendersWithStylesAndScript() = runPlanTabRendersWithStylesAndScript()
}
