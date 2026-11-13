package efcore.visualizer

import org.testng.annotations.Test
import kotlin.test.assertEquals

/** The progress text, without Rider: same wording as the VS Code extension. */
class PlanProgressTest {
    @Test fun messageShowsTheElapsedTimeAndTheHintAfterThreeSeconds() {
        assertEquals("Getting the query plan of query…", PlanProgress.message("query", 0))
        assertEquals("Getting the query plan of query (2 s)", PlanProgress.message("query", 2))
        assertEquals("Getting the query plan of query (3 s). ${PlanProgress.HINT}", PlanProgress.message("query", 3))
    }
}
