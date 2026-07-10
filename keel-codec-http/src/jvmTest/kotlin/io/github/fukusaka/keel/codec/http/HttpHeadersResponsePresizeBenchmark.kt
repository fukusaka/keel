package io.github.fukusaka.keel.codec.http

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Per-cycle JVM allocation for the **unpooled response-construction**
 * header path — the `HttpResponse.ok` / `HttpHeaders.build` case that,
 * unlike the pooled decoder path, allocates fresh storage on every
 * response.
 *
 * Isolates the header-storage over-allocation from the response's data
 * allocations: [headersOnly] / [headersOnlyHinted] build exactly the
 * two-header (`Content-Type` + `Content-Length`) set a typical response
 * carries, with constant string values, so the measured bytes are the
 * `HttpHeaders` instance + slot `IntArray` + `stringBacking` `ArrayList`
 * and nothing else. [fullResponse] measures the whole
 * `HttpResponse.ok(text)` for context (its body `ByteArray` and the
 * `Content-Length` `toString()` are unavoidable data allocations).
 * [headerHeavy] builds a ten-header set through the un-hinted `build {}`
 * as a regression guard for the shared growth path.
 *
 * Result (JVM, `getThreadAllocatedBytes`, median of 9 × 200k iterations)
 * — the change that gave `HttpHeaders.build` an entry-count hint so the
 * response path sizes storage to exactly its field count instead of the
 * eight-slot [HttpHeaders] default:
 *
 * ```
 *   headers-only build {}   (control)   344 bytes/cycle
 *   headers-only build(2){} (treatment) 176 bytes/cycle   -168 (-49%)
 *   full HttpResponse.ok(text)          392 -> 224        -168 (-43%)
 *   header-heavy build {}   (guard)     1144 -> 1144      unchanged
 * ```
 *
 * The saving is the slot `IntArray` dropping from `IntArray(40)` (eight
 * slots x stride 5) to `IntArray(10)` and the string store from
 * `ArrayList(16)` to `ArrayList(4)`. The un-hinted `build {}` path is
 * byte-identical, so header-heavy requests do not regress.
 */
// @Ignore: one-time measurement (no functional assertion) — a decision
// aid that recorded the response-presize saving, kept for
// re-verification. The verified numbers + conclusion are the class KDoc
// above; the functional guarantee (hinted == un-hinted contents) is
// pinned by HttpHeadersReserveTest, which does run in the gate.
// Re-run: remove @Ignore, then
//   ./gradlew :keel-codec-http:jvmTest --tests "*HttpHeadersResponsePresizeBenchmark"
@Ignore
class HttpHeadersResponsePresizeBenchmark {

    private val tmx = ManagementFactory.getThreadMXBean() as ThreadMXBean

    private var sink = 0

    private fun measure(iterations: Int, body: () -> Unit): Long {
        repeat(WARMUP) { body() }
        val tid = Thread.currentThread().threadId()
        val start = tmx.getThreadAllocatedBytes(tid)
        repeat(iterations) { body() }
        val end = tmx.getThreadAllocatedBytes(tid)
        return (end - start) / iterations
    }

    private fun median(trials: Int, m: () -> Long): Long =
        LongArray(trials) { m() }.also { it.sort() }[trials / 2]

    private fun headersOnly() {
        val h = HttpHeaders.build {
            add("Content-Type", "text/plain")
            add("Content-Length", "13")
        }
        sink += h.size
    }

    private fun headersOnlyHinted() {
        val h = HttpHeaders.build(2) {
            add("Content-Type", "text/plain")
            add("Content-Length", "13")
        }
        sink += h.size
    }

    private fun fullResponse() {
        val r = HttpResponse.ok("Hello, World!")
        sink += r.headers.size
    }

    private fun headerHeavy() {
        val h = HttpHeaders.build {
            add("Host", "example.com")
            add("User-Agent", "curl/8.0")
            add("Accept", "*/*")
            add("Accept-Encoding", "gzip, deflate")
            add("Connection", "keep-alive")
            add("Cache-Control", "no-cache")
            add("Pragma", "no-cache")
            add("Referer", "https://example.com/")
            add("Cookie", "session=abc123")
            add("X-Request-Id", "0123456789")
        }
        sink += h.size
    }

    @Test
    fun `report unpooled response header allocation`() {
        val control = median(TRIALS) { measure(ITERS) { headersOnly() } }
        val treatment = median(TRIALS) { measure(ITERS) { headersOnlyHinted() } }
        val full = median(TRIALS) { measure(ITERS) { fullResponse() } }
        val heavy = median(TRIALS) { measure(ITERS) { headerHeavy() } }
        println("[presize] headers-only build{}    (control):   $control bytes/cycle")
        println("[presize] headers-only build(2){}  (treatment): $treatment bytes/cycle")
        println("[presize] full HttpResponse.ok(text):          $full bytes/cycle")
        println("[presize] header-heavy build{}     (guard):     $heavy bytes/cycle")
        println("[presize] sink=$sink")
    }

    private companion object {
        const val WARMUP = 10_000
        const val ITERS = 200_000
        const val TRIALS = 9
    }
}
