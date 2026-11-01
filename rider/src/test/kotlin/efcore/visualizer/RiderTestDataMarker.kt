package efcore.visualizer

import com.jetbrains.rider.test.framework.testData.IRiderTestDataMarker
import java.nio.file.Path

/** Tells Rider's test framework where test solutions and gold files live (found by package lookup). */
@Suppress("unused")
object RiderTestDataMarker : IRiderTestDataMarker {
    override val testDataFromRoot: Path
        get() = Path.of("src/test/testData")
}
