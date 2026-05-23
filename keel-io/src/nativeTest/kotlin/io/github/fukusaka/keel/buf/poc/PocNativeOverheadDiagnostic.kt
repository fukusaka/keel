package io.github.fukusaka.keel.buf.poc

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.time.TimeSource
import kotlin.time.measureTime

/**
 * Native AOT bench-framework overhead diagnostic.
 *
 * The PoC benches showed Native scenarios sitting ~80-500× slower than
 * their JVM counterparts even on the baseline single-seg `IoBuf` path.
 * That smells like measurement infrastructure overhead — production
 * keel runs Native engines and a 500× slowdown would have been
 * observed years ago. This file isolates the candidates:
 *
 * **Variants** (all do roughly the same byte-level work):
 *
 *  1. **tight-direct**: pure arithmetic loop, no IoBuf, no lambda.
 *     Calibrates the for-loop + nanoTime baseline.
 *  2. **tight-getByte**: direct `IoBuf.getByte` calls in a tight loop,
 *     no lambda wrapper. Measures the actual cost of a single IoBuf
 *     read on Native.
 *  3. **lambda-getByte (measureTime)**: `IoBuf.getByte` called from
 *     inside a `measureTime { repeat(N) { ... } }` block — what the
 *     existing PoC bench does. Identifies framework cost on top of
 *     the actual work.
 *  4. **lambda-getByte (TimeSource.markNow)**: same shape with the
 *     monotonic time source surfaced directly, in case
 *     `kotlin.time.measureTime`'s contract injects extra work.
 *
 * Comparing 1↔2 surfaces the IoBuf interface dispatch cost; comparing
 * 2↔3 surfaces the `Function0` / lambda capture cost; comparing 3↔4
 * surfaces any `measureTime` overhead.
 */
@OptIn(ExperimentalForeignApi::class)
class PocNativeOverheadDiagnostic {

    private val payload = ByteArray(1024) { (it and 0xFF).toByte() }

    private fun freshBuf(): IoBuf {
        val buf = DefaultAllocator.allocate(1024)
        buf.writeByteArray(payload, 0, payload.size)
        return buf
    }

    @Test
    fun `report native overhead diagnostic`() {
        println("=== Native bench-framework overhead diagnostic ===")
        println("    each variant runs $ITERS iterations of byte-level work")
        println()
        println("                                                ns/iter")
        runTightDirect()
        runTightGetByte()
        runLambdaGetByteMeasureTime()
        runLambdaGetByteMarkNow()
    }

    /**
     * Pure arithmetic loop — calibrates the for-loop + nanoTime
     * cost without any IoBuf or lambda involvement.
     */
    private fun runTightDirect() {
        var sum: Long = 0
        // Warm
        for (j in 0 until WARMUP) {
            for (i in 0 until 1024) sum += i.toLong()
        }
        val mark = TimeSource.Monotonic.markNow()
        for (j in 0 until ITERS) {
            for (i in 0 until 1024) sum += i.toLong()
        }
        val ns = mark.elapsedNow().inWholeNanoseconds / ITERS
        sinkSum = sum
        println("  1. tight-direct (no IoBuf, no lambda)        $ns")
    }

    /**
     * Direct IoBuf.getByte calls in a tight loop. No lambda — the
     * for-loop body invokes the interface method directly, so the
     * cost is `getByte` dispatch + execution.
     */
    private fun runTightGetByte() {
        val buf = freshBuf()
        try {
            var sum: Long = 0
            for (j in 0 until WARMUP) {
                for (i in 0 until 1024) sum += buf.getByte(i).toLong()
            }
            val mark = TimeSource.Monotonic.markNow()
            for (j in 0 until ITERS) {
                for (i in 0 until 1024) sum += buf.getByte(i).toLong()
            }
            val ns = mark.elapsedNow().inWholeNanoseconds / ITERS
            sinkSum = sum
            println("  2. tight-getByte (no lambda)                 $ns")
        } finally {
            buf.release()
        }
    }

    /**
     * Same byte-level work but wrapped in `measureTime { repeat(N) {} }`
     * — what the existing PoC bench does. Diff vs (2) is the
     * lambda + Function0 dispatch + measureTime cost combined.
     */
    private fun runLambdaGetByteMeasureTime() {
        val buf = freshBuf()
        try {
            var sum: Long = 0
            repeat(WARMUP) {
                for (i in 0 until 1024) sum += buf.getByte(i).toLong()
            }
            val elapsed = measureTime {
                repeat(ITERS) {
                    for (i in 0 until 1024) sum += buf.getByte(i).toLong()
                }
            }
            sinkSum = sum
            println("  3. lambda-getByte via measureTime+repeat     ${elapsed.inWholeNanoseconds / ITERS}")
        } finally {
            buf.release()
        }
    }

    /**
     * Same as (3) but uses `TimeSource.Monotonic.markNow()` directly
     * instead of `measureTime { ... }`. If (4) is materially faster
     * than (3), `measureTime` itself has overhead.
     */
    private fun runLambdaGetByteMarkNow() {
        val buf = freshBuf()
        try {
            var sum: Long = 0
            repeat(WARMUP) {
                for (i in 0 until 1024) sum += buf.getByte(i).toLong()
            }
            val mark = TimeSource.Monotonic.markNow()
            repeat(ITERS) {
                for (i in 0 until 1024) sum += buf.getByte(i).toLong()
            }
            val ns = mark.elapsedNow().inWholeNanoseconds / ITERS
            sinkSum = sum
            println("  4. lambda-getByte via markNow+repeat         $ns")
        } finally {
            buf.release()
        }
    }

    companion object {
        @Suppress("MemberVisibilityCanBePrivate")
        var sinkSum: Long = 0

        private const val WARMUP = 100
        private const val ITERS = 10_000
    }
}
