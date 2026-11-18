package efcore.visualizer

import org.opentest4j.TestAbortedException

// Rider 2026.3's test framework runs on JUnit 5.

/** Ends the current test as skipped. */
fun skipTest(reason: String): Nothing = throw TestAbortedException(reason)
