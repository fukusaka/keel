package io.github.fukusaka.keel.buf

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Measures per-operation cost of [IoBufAsciiText] across the 7
 * operation axes (C1-C7) that L7 hot-path consumers will exercise.
 *
 * Compared against [String] direct access as the baseline (the type
 * keel currently returns from `HttpHeaders.get(name)`).
 *
 * - **C1 — construct alloc**: bytes per `IoBufAsciiText(buf, start, length)` call.
 * - **C2 — length getter**: latency of `.length`. Expected near-zero
 *   (final field read).
 * - **C3 — `get(i): Char`**: per-char latency (byte read + mask + Char
 *   conversion vs `String.get(i)` direct char[] read).
 * - **C4 — `subSequence(start, end)`**: bytes per sub-view alloc vs
 *   `String.substring` (which copies bytes into a new String).
 * - **C5 — `toString()`**: bytes per materialisation (ByteArray +
 *   decodeToString) vs `String.toString()` (identity, zero alloc).
 * - **C6 — `contentEquals(other)`**: latency of value compare vs
 *   `String.contentEquals`.
 * - **C7 — `hashCode()`**: per-call latency. View recomputes each call
 *   (no cache); `String` caches after first call.
 *
 * Format mirrors
 * [io.github.fukusaka.keel.engine.netty.NettyReadPathAllocationBenchmark]
 * (`ThreadMXBean.getThreadAllocatedBytes` for alloc, `System.nanoTime`
 * for latency, median over `TRIALS` trials).
 */
// @Ignore: one-time measurement (no functional assertion) — a decision
// aid that caught no regression, so it is not run in the gate / CI; kept
// for re-verification. The verified content + conclusion is the class
// KDoc above.
// Re-run: remove @Ignore, then
//   ./gradlew :keel-io:jvmTest --tests "*IoBufAsciiTextBenchmark"
@Ignore
class IoBufAsciiTextBenchmark {

    private val tmx = ManagementFactory.getThreadMXBean() as ThreadMXBean
    private val payloadBytes = PAYLOAD.encodeToByteArray()
    private val buf = DefaultAllocator.allocate(payloadBytes.size).apply {
        writeByteArray(payloadBytes, 0, payloadBytes.size)
    }
    private val view = IoBufAsciiText(buf, 0, payloadBytes.size)

    private fun measureBytesPerIter(iterations: Int, body: () -> Unit): Long {
        repeat(WARMUP) { body() }
        val tid = Thread.currentThread().threadId()
        val start = tmx.getThreadAllocatedBytes(tid)
        repeat(iterations) { body() }
        val end = tmx.getThreadAllocatedBytes(tid)
        return (end - start) / iterations
    }

    /** Returns nanoseconds per single op. */
    private fun measureNsPerOp(iterations: Int, body: () -> Unit): Long {
        repeat(WARMUP) { body() }
        val start = System.nanoTime()
        repeat(iterations) { body() }
        val end = System.nanoTime()
        return (end - start) / iterations
    }

    private fun median(trials: Int, m: () -> Long): Long =
        LongArray(trials) { m() }.also { it.sort() }[trials / 2]

    private var sink = 0 // dead-code-elimination guard

    @Test
    fun `IoBufAsciiText vs String — 7 axes`() {
        // C1 — construct alloc
        val c1View = median(TRIALS) {
            measureBytesPerIter(ITERS) {
                val v = IoBufAsciiText(buf, 0, payloadBytes.size)
                sink += v.length // prevent EE
            }
        }
        val c1String = median(TRIALS) {
            measureBytesPerIter(ITERS) {
                // baseline: String construction from byte[] is the closest
                // analogue (cf. HttpRequestDecoder currently materializes
                // String per header via decodeToString on a ByteArray copy)
                val s = payloadBytes.decodeToString()
                sink += s.length
            }
        }

        // C2 — length getter latency (expect inlined to near-zero, both)
        val c2View = median(TRIALS) {
            measureNsPerOp(ITERS_NS) { sink += view.length }
        }
        val c2String = median(TRIALS) {
            measureNsPerOp(ITERS_NS) { sink += PAYLOAD.length }
        }

        // C3 — per-char get(i)
        val c3View = median(TRIALS) {
            measureNsPerOp(ITERS_NS) { sink += view[CHAR_INDEX].code }
        }
        val c3String = median(TRIALS) {
            measureNsPerOp(ITERS_NS) { sink += PAYLOAD[CHAR_INDEX].code }
        }

        // C4 — subSequence alloc (view) vs substring alloc (String)
        val c4View = median(TRIALS) {
            measureBytesPerIter(ITERS) {
                val sub = view.subSequence(0, SUB_END)
                sink += sub.length
            }
        }
        val c4String = median(TRIALS) {
            measureBytesPerIter(ITERS) {
                val sub = PAYLOAD.substring(0, SUB_END)
                sink += sub.length
            }
        }

        // C5 — toString materialisation
        val c5View = median(TRIALS) {
            measureBytesPerIter(ITERS) {
                val s = view.toString()
                sink += s.length
            }
        }
        val c5String = median(TRIALS) {
            measureBytesPerIter(ITERS) {
                @Suppress("StringTemplateOnlyVariable")
                val s = PAYLOAD.toString() // identity (cached)
                sink += s.length
            }
        }

        // C6 — contentEquals (member overload, char-vs-byte loop)
        val c6View = median(TRIALS) {
            measureNsPerOp(ITERS_NS) { if (view.contentEquals(PAYLOAD)) sink++ }
        }
        val c6String = median(TRIALS) {
            measureNsPerOp(ITERS_NS) { if (PAYLOAD.contentEquals(PAYLOAD)) sink++ }
        }

        // C6-ascii — contentEqualsAscii (byte-vs-byte, no char conversion)
        val payloadBytesConst = PAYLOAD.encodeToByteArray()
        val c6ViewAscii = median(TRIALS) {
            measureNsPerOp(ITERS_NS) { if (view.contentEqualsAscii(payloadBytesConst)) sink++ }
        }

        // C7 — hashCode. View caches after first call (same as String).
        // Each measurement here is a steady-state call: both view and
        // String have already computed once during warmup, so we are
        // comparing cached-vs-cached.
        val c7View = median(TRIALS) {
            measureNsPerOp(ITERS_NS) { sink += view.hashCode() }
        }
        val c7String = median(TRIALS) {
            measureNsPerOp(ITERS_NS) { sink += PAYLOAD.hashCode() }
        }

        println(
            "=== IoBufAsciiText vs String (payload='$PAYLOAD', ${payloadBytes.size} bytes, iters=$ITERS × $TRIALS) ===",
        )
        println("  C1 construct      view=$c1View B   string(decode)=$c1String B")
        println("  C2 length         view=$c2View ns  string=$c2String ns")
        println("  C3 get(i)         view=$c3View ns  string=$c3String ns")
        println("  C4 subSequence    view=$c4View B   substring=$c4String B")
        println("  C5 toString       view=$c5View B   string(identity)=$c5String B")
        println("  C6 contentEquals       view=$c6View ns       string=$c6String ns")
        println("  C6-ascii contentEqualsAscii(byte[])  view=$c6ViewAscii ns  (byte-vs-byte, no Char convert)")
        println("  C7 hashCode (cached)   view=$c7View ns       string(cached)=$c7String ns")
        println("  (sink=$sink — DCE guard)")
    }

    companion object {
        // 16-char payload approximating a typical HTTP header value
        // (`application/json`, `text/event-stream`, etc.)
        private const val PAYLOAD = "application/json"
        private const val CHAR_INDEX = 5 // arbitrary mid-string index
        private const val SUB_END = 11 // substring "application"
        private const val WARMUP = 2_000
        private const val ITERS = 10_000 // for alloc bench (heavier)
        private const val ITERS_NS = 1_000_000 // for ns/op bench (cheaper)
        private const val TRIALS = 5
    }
}
