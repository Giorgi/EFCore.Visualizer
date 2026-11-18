package efcore.visualizer

import org.testng.SkipException

// Rider 2026.2's test framework runs on TestNG.

/** Ends the current test as skipped. */
fun skipTest(reason: String): Nothing = throw SkipException(reason)
