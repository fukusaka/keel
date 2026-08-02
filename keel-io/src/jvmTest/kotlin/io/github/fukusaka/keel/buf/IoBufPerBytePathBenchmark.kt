package io.github.fukusaka.keel.buf

import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Per-byte hot-path microbench for [DirectIoBuf] following the
 * [AbstractIoBuf] extraction in PR #617.
 *
 * Compares three receiver-type call sites for [IoBuf.writeByte] /
 * [IoBuf.readByte] / [IoBuf.getByte]:
 *
 * - **concrete** — receiver typed as [DirectIoBuf] (the engine /
 *   allocator path where the concrete type is statically known),
 * - **abstract** — receiver typed as [AbstractIoBuf] (e.g. the
 *   intrusive pool freelist if it were widened),
 * - **interface** — receiver typed as [IoBuf] (the codec / pipeline
 *   path where ownership is handed across the public surface).
 *
 * Goal: verify the KDoc claim that per-byte methods are abstract +
 * the override sits on the concrete class, so HotSpot can devirtualize
 * the concrete-typed call site and the abstract / interface sites only
 * pay vtable / itable dispatch when no monomorphic profile applies.
 *
 * Bench shape mirrors [IoBufAsciiTextBenchmark]: warmup, median over
 * `TRIALS` trials, [sink] dead-code-elimination guard, `System.nanoTime`
 * for latency.
 *
 * Steady-state HotSpot will likely monomorphize all three since only
 * one concrete subtype is loaded in this test JVM — the comparison
 * therefore quantifies the *worst-case* dispatch envelope; production
 * processes that load all three IoBuf subtypes (`DirectIoBuf` JVM
 * engines + `NettyByteBufIoBuf` Netty) will fall back to bimorphic /
 * megamorphic and pay closer to the abstract / interface numbers.
 */
// @Ignore: one-time measurement (no functional assertion) — a decision
// aid that caught no regression, so it is not run in the gate / CI; kept
// for re-verification. The verified content + conclusion is the class
// KDoc above.
// Re-run: remove @Ignore, then
//   ./gradlew :keel-io:jvmTest --tests "*IoBufPerBytePathBenchmark"
@Ignore
class IoBufPerBytePathBenchmark {

    private val buf = DirectIoBuf(CAPACITY)
    private val asAbstract: AbstractIoBuf = buf
    private val asInterface: IoBuf = buf

    private var sink = 0

    /** Returns nanoseconds per loop (one [body] invocation). */
    private fun measureNsPerOp(iterations: Int, body: () -> Unit): Double {
        repeat(WARMUP) { body() }
        val start = System.nanoTime()
        repeat(iterations) { body() }
        val end = System.nanoTime()
        return (end - start).toDouble() / iterations
    }

    private fun median(trials: Int, m: () -> Double): Double =
        DoubleArray(trials) { m() }.also { it.sort() }[trials / 2]

    private fun report(label: String, nsPerLoop: Double) {
        val nsPerByte = nsPerLoop / BYTES_PER_OP
        val loopFmt = (kotlin.math.round(nsPerLoop * 10.0) / 10.0).toString()
        val byteFmt = (kotlin.math.round(nsPerByte * 100.0) / 100.0).toString()
        println("  $label loop=$loopFmt ns   per-byte=$byteFmt ns")
    }

    private fun fillForRead() {
        buf.clear()
        for (i in 0 until CAPACITY) buf.writeByte(i.toByte())
    }

    @Test
    fun `per-byte writeByte — concrete vs abstract vs interface`() {
        // writeByte fills the buffer; clear between iterations.
        val concrete = median(TRIALS) {
            measureNsPerOp(CHUNK_ITERS) {
                buf.clear()
                for (i in 0 until BYTES_PER_OP) buf.writeByte(i.toByte())
                sink += buf.writerIndex
            }
        }
        val abstractTyped = median(TRIALS) {
            measureNsPerOp(CHUNK_ITERS) {
                asAbstract.clear()
                for (i in 0 until BYTES_PER_OP) asAbstract.writeByte(i.toByte())
                sink += asAbstract.writerIndex
            }
        }
        val interfaceTyped = median(TRIALS) {
            measureNsPerOp(CHUNK_ITERS) {
                asInterface.clear()
                for (i in 0 until BYTES_PER_OP) asInterface.writeByte(i.toByte())
                sink += asInterface.writerIndex
            }
        }

        println("=== writeByte $BYTES_PER_OP-byte loop (iters=$CHUNK_ITERS × $TRIALS) ===")
        report("concrete  receiver=DirectIoBuf  ", concrete)
        report("abstract  receiver=AbstractIoBuf", abstractTyped)
        report("interface receiver=IoBuf        ", interfaceTyped)
        println("  (sink=$sink — DCE guard)")
    }

    @Test
    fun `per-byte readByte — concrete vs abstract vs interface`() {
        val concrete = median(TRIALS) {
            measureNsPerOp(CHUNK_ITERS) {
                fillForRead()
                for (i in 0 until BYTES_PER_OP) sink += buf.readByte().toInt()
            }
        }
        val abstractTyped = median(TRIALS) {
            measureNsPerOp(CHUNK_ITERS) {
                fillForRead()
                for (i in 0 until BYTES_PER_OP) sink += asAbstract.readByte().toInt()
            }
        }
        val interfaceTyped = median(TRIALS) {
            measureNsPerOp(CHUNK_ITERS) {
                fillForRead()
                for (i in 0 until BYTES_PER_OP) sink += asInterface.readByte().toInt()
            }
        }

        println("=== readByte $BYTES_PER_OP-byte loop (iters=$CHUNK_ITERS × $TRIALS) ===")
        report("concrete  receiver=DirectIoBuf  ", concrete)
        report("abstract  receiver=AbstractIoBuf", abstractTyped)
        report("interface receiver=IoBuf        ", interfaceTyped)
        println("  (sink=$sink — DCE guard)")
    }

    @Test
    fun `per-byte getByte — concrete vs abstract vs interface`() {
        fillForRead()
        val concrete = median(TRIALS) {
            measureNsPerOp(CHUNK_ITERS) {
                for (i in 0 until BYTES_PER_OP) sink += buf.getByte(i).toInt()
            }
        }
        val abstractTyped = median(TRIALS) {
            measureNsPerOp(CHUNK_ITERS) {
                for (i in 0 until BYTES_PER_OP) sink += asAbstract.getByte(i).toInt()
            }
        }
        val interfaceTyped = median(TRIALS) {
            measureNsPerOp(CHUNK_ITERS) {
                for (i in 0 until BYTES_PER_OP) sink += asInterface.getByte(i).toInt()
            }
        }

        println("=== getByte $BYTES_PER_OP-byte loop (iters=$CHUNK_ITERS × $TRIALS) ===")
        report("concrete  receiver=DirectIoBuf  ", concrete)
        report("abstract  receiver=AbstractIoBuf", abstractTyped)
        report("interface receiver=IoBuf        ", interfaceTyped)
        println("  (sink=$sink — DCE guard)")
    }

    companion object {
        private const val CAPACITY = 256
        private const val BYTES_PER_OP = 128
        private const val WARMUP = 2_000
        private const val CHUNK_ITERS = 100_000
        private const val TRIALS = 5
    }
}
