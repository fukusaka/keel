package io.github.fukusaka.keel.benchmark

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [formatClientResultLine] to the `String.format` output it replaced.
 *
 * The JVM harness used to build its result line with `"%s|%.0f|%.3f|…".format(…)`
 * directly. That moved to a shared formatter so the Native harness emits the
 * identical shape, which means the JVM's own output changed implementation
 * without intending to change its bytes — `bench-client.sh` parses these lines,
 * and recorded results are compared across runs. These assert the substitution
 * is exact, including the rounding of a value sitting on a decimal boundary.
 */
class ClientHarnessFormatTest {

    private fun legacy(
        name: String,
        reqPerSec: Double,
        p50: Double,
        p99: Double,
        p999: Double,
        max: Double,
        bytesPerOp: String,
        errors: Long,
    ): String = "%s|%.0f|%.3f|%.3f|%.3f|%.3f|%s|%d".format(name, reqPerSec, p50, p99, p999, max, bytesPerOp, errors)

    private fun assertMatches(
        reqPerSec: Double,
        p50: Double,
        p99: Double,
        p999: Double,
        max: Double,
        bytesPerOp: String,
        errors: Long,
    ) {
        val name = "keel/hello"
        assertEquals(
            legacy(name, reqPerSec, p50, p99, p999, max, bytesPerOp, errors),
            formatClientResultLine(name, reqPerSec, p50, p99, p999, max, bytesPerOp, errors),
            "rps=$reqPerSec p50=$p50 p99=$p99 p99.9=$p999 max=$max b/op=$bytesPerOp err=$errors",
        )
    }

    @Test
    fun `a measured result line matches the format it replaced`() {
        // Values from a real run, so the common case is pinned literally.
        assertMatches(147274.0, 0.648, 1.419, 2.652, 33.653, "3926", 0)
        assertMatches(27035.4, 0.036, 0.056, 0.091, 1.034, "3532", 0)
        assertMatches(35860.0, 0.311, 119.865, 249.037, 501.744, "n/a", 578)
    }

    @Test
    fun `zero and small magnitudes match`() {
        assertMatches(0.0, 0.0, 0.0, 0.0, 0.0, "n/a", 0)
        assertMatches(1.0, 0.001, 0.002, 0.003, 0.004, "0", 1)
        // Sub-millisecond latencies round to three places the same way.
        assertMatches(999.5, 0.0004, 0.0005, 0.0006, 0.0015, "n/a", 0)
    }

    @Test
    fun `random values match across the plausible range`() {
        val random = Random(seed = 7_19)
        repeat(2000) {
            assertMatches(
                reqPerSec = random.nextDouble(0.0, 500_000.0),
                p50 = random.nextDouble(0.0, 10.0),
                p99 = random.nextDouble(0.0, 100.0),
                p999 = random.nextDouble(0.0, 500.0),
                max = random.nextDouble(0.0, 2000.0),
                bytesPerOp = if (it % 3 == 0) "n/a" else random.nextInt(0, 50_000).toString(),
                errors = random.nextLong(0, 10_000),
            )
        }
    }
}
