package io.github.fukusaka.keel.buf

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlin.time.measureTime

/**
 * Native per-byte hot-path microbench for [NativeIoBuf] following the
 * [AbstractIoBuf] extraction in PR #617.
 *
 * Compares three receiver-type call sites for [IoBuf.writeByte] /
 * [IoBuf.readByte] / [IoBuf.getByte]:
 *
 * - **concrete** — receiver typed as [NativeIoBuf] (static dispatch,
 *   the engine / allocator path),
 * - **abstract** — receiver typed as [AbstractIoBuf] (virtual dispatch
 *   through the new common base),
 * - **interface** — receiver typed as [IoBuf] (interface dispatch via
 *   K/N's itable-equivalent, the codec / pipeline path).
 *
 * Goal: quantify the dispatch overhead introduced by PR #617 on the
 * K/N Apple Silicon target — the optimiser may not devirtualize
 * abstract / interface receivers the way HotSpot does on JVM. The
 * KDoc on [AbstractIoBuf] claims engines and codecs always hold the
 * concrete type so virtual dispatch is avoided; this bench measures
 * the gap when that assumption does not hold.
 *
 * Methodology: 2-second warmup + 3-second trial × 3 runs, median ns/op.
 * Iteration count chosen so each op (one full [BYTES_PER_OP]-byte scan)
 * takes a few μs to amortise [TimeSource.Monotonic] overhead.
 *
 * Runs as a `@Test` so existing `<target>Test` gradle wiring picks it
 * up. Not a regression gate — prints results, never fails.
 */
// @Ignore: one-time measurement (no functional assertion) — a decision
// aid that caught no regression, so it is not run in the gate / CI; kept
// for re-verification. The verified content + conclusion is the class
// KDoc above.
// Re-run: remove @Ignore, then
//   ./gradlew :keel-io:<target>Test --tests "*IoBufPerBytePathBenchmark"
@Ignore
class IoBufPerBytePathBenchmark {

    @Test
    fun `per-byte writeByte — concrete vs abstract vs interface`() {
        @Suppress("IoBufLeak")
        val buf = NativeIoBuf(CAPACITY)
        try {
            val asAbstract: AbstractIoBuf = buf
            val asInterface: IoBuf = buf

            val nsConcrete = benchVariant {
                buf.clear()
                for (i in 0 until BYTES_PER_OP) buf.writeByte(i.toByte())
                blackhole += buf.writerIndex.toLong()
            }
            val nsAbstract = benchVariant {
                asAbstract.clear()
                for (i in 0 until BYTES_PER_OP) asAbstract.writeByte(i.toByte())
                blackhole += asAbstract.writerIndex.toLong()
            }
            val nsInterface = benchVariant {
                asInterface.clear()
                for (i in 0 until BYTES_PER_OP) asInterface.writeByte(i.toByte())
                blackhole += asInterface.writerIndex.toLong()
            }

            println("=== writeByte $BYTES_PER_OP-byte loop (median of 3 × ${TRIAL_MS}ms trials) ===")
            report("concrete  receiver=NativeIoBuf  ", nsConcrete)
            report("abstract  receiver=AbstractIoBuf", nsAbstract)
            report("interface receiver=IoBuf        ", nsInterface)
            println("  (blackhole=$blackhole — DCE guard)")
        } finally {
            buf.release()
        }
    }

    @Test
    fun `per-byte readByte — concrete vs abstract vs interface`() {
        @Suppress("IoBufLeak")
        val buf = NativeIoBuf(CAPACITY).also { b ->
            for (i in 0 until CAPACITY) b.writeByte(i.toByte())
        }
        try {
            val asAbstract: AbstractIoBuf = buf
            val asInterface: IoBuf = buf

            val nsConcrete = benchVariant {
                buf.readerIndex = 0
                var sum = 0L
                for (i in 0 until BYTES_PER_OP) sum += buf.readByte().toLong()
                blackhole += sum
            }
            val nsAbstract = benchVariant {
                asAbstract.readerIndex = 0
                var sum = 0L
                for (i in 0 until BYTES_PER_OP) sum += asAbstract.readByte().toLong()
                blackhole += sum
            }
            val nsInterface = benchVariant {
                asInterface.readerIndex = 0
                var sum = 0L
                for (i in 0 until BYTES_PER_OP) sum += asInterface.readByte().toLong()
                blackhole += sum
            }

            println("=== readByte $BYTES_PER_OP-byte loop (median of 3 × ${TRIAL_MS}ms trials) ===")
            report("concrete  receiver=NativeIoBuf  ", nsConcrete)
            report("abstract  receiver=AbstractIoBuf", nsAbstract)
            report("interface receiver=IoBuf        ", nsInterface)
            println("  (blackhole=$blackhole — DCE guard)")
        } finally {
            buf.release()
        }
    }

    @Test
    fun `per-byte getByte — concrete vs abstract vs interface`() {
        @Suppress("IoBufLeak")
        val buf = NativeIoBuf(CAPACITY).also { b ->
            for (i in 0 until CAPACITY) b.writeByte(i.toByte())
        }
        try {
            val asAbstract: AbstractIoBuf = buf
            val asInterface: IoBuf = buf

            val nsConcrete = benchVariant {
                var sum = 0L
                for (i in 0 until BYTES_PER_OP) sum += buf.getByte(i).toLong()
                blackhole += sum
            }
            val nsAbstract = benchVariant {
                var sum = 0L
                for (i in 0 until BYTES_PER_OP) sum += asAbstract.getByte(i).toLong()
                blackhole += sum
            }
            val nsInterface = benchVariant {
                var sum = 0L
                for (i in 0 until BYTES_PER_OP) sum += asInterface.getByte(i).toLong()
                blackhole += sum
            }

            println("=== getByte $BYTES_PER_OP-byte loop (median of 3 × ${TRIAL_MS}ms trials) ===")
            report("concrete  receiver=NativeIoBuf  ", nsConcrete)
            report("abstract  receiver=AbstractIoBuf", nsAbstract)
            report("interface receiver=IoBuf        ", nsInterface)
            println("  (blackhole=$blackhole — DCE guard)")
        } finally {
            buf.release()
        }
    }

    private inline fun benchVariant(body: () -> Unit): Double {
        // Warmup
        val warmDeadline = TimeSource.Monotonic.markNow()
        while (warmDeadline.elapsedNow().toDouble(DurationUnit.MILLISECONDS) < WARMUP_MS) {
            repeat(1_000) { body() }
        }
        // 3 trials, take median. Use `measureTime { ... }` to capture
        // the actual loop duration in one read — `elapsedNow()` twice
        // (once in the while condition, once for ns) skews the window.
        val nsPerOp = DoubleArray(3)
        for (t in 0 until 3) {
            var iters = 0L
            val elapsed = measureTime {
                val deadline = TimeSource.Monotonic.markNow()
                while (deadline.elapsedNow().toDouble(DurationUnit.MILLISECONDS) < TRIAL_MS) {
                    repeat(1_000) {
                        body()
                        iters++
                    }
                }
            }
            nsPerOp[t] = elapsed.toDouble(DurationUnit.NANOSECONDS) / iters.toDouble()
        }
        nsPerOp.sort()
        return nsPerOp[1]
    }

    private fun report(label: String, nsPerOp: Double) {
        val nsPerByte = nsPerOp / BYTES_PER_OP
        val nsOpRounded = kotlin.math.round(nsPerOp * 10.0) / 10.0
        val nsByteRounded = kotlin.math.round(nsPerByte * 100.0) / 100.0
        println("  $label loop=$nsOpRounded ns   per-byte=$nsByteRounded ns")
    }

    companion object {
        private const val CAPACITY = 256
        private const val BYTES_PER_OP = 128
        private const val WARMUP_MS = 2_000L
        private const val TRIAL_MS = 3_000L

        @kotlin.concurrent.Volatile
        private var blackhole: Long = 0
    }
}
