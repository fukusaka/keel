package io.github.fukusaka.keel.engine.kqueue.poc

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.asNativePointer
import io.github.fukusaka.keel.buf.poc.appendNewSegment
import io.github.fukusaka.keel.buf.poc.cand1.Cand1IoBuf
import io.github.fukusaka.keel.buf.poc.cand1.Cand1IoBufImpl
import io.github.fukusaka.keel.buf.poc.cand2.Cand2IoBuf
import io.github.fukusaka.keel.buf.poc.cand2.Cand2IoBufImpl
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.plus
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * Cross-module Native counterpart to `PocMultiSegCrossModuleBenchmark`
 * in `keel-engine-nio` jvmTest.
 *
 * Validates that Kotlin/Native release-mode AOT preserves the cand1
 * SAM-lambda advantage when caller and callee are split across
 * separate compilation units. Whole-program LTO should pull both back
 * into the test binary and keep the cand1 callback inlined; if it
 * does not, the gap shrinks toward the cand2 list path.
 *
 * Run via the provisional release-mode test binary:
 *
 *     ./gradlew :keel-engine-kqueue:linkReleaseReleaseTestMacosArm64
 *     ./keel-engine-kqueue/build/bin/macosArm64/releaseReleaseTest/release.kexe \\
 *         --ktest_filter='*PocMultiSegCrossModuleNativeBenchmark*'
 */
@OptIn(ExperimentalForeignApi::class)
class PocMultiSegCrossModuleNativeBenchmark {

    private val segCap = 512
    private val maxCap = 32 * segCap

    private val payload = ByteArray(8 * segCap - 32) { (it and 0xFF).toByte() }

    private fun makeCand1(): Cand1IoBuf {
        val buf = Cand1IoBufImpl(DefaultAllocator, segCap, maxCap)
        repeat(7) { buf.appendNewSegment(DefaultAllocator, segCap) }
        buf.writeByteArray(payload, 0, payload.size)
        return buf
    }

    private fun makeCand2(): Cand2IoBuf {
        val buf = Cand2IoBufImpl(DefaultAllocator, segCap, maxCap)
        repeat(7) { buf.appendNewSegment(DefaultAllocator, segCap) }
        buf.writeByteArray(payload, 0, payload.size)
        return buf
    }

    @Suppress("unused")
    private var sink: Long = 0

    @Test
    fun `report cross-module iov-build native`() {
        println("=== PoC multi-seg IoBuf cross-module iov-build (Native, engine-kqueue) ===")
        println("    8 segments x 512 B = 4064 B / cycle (payload size)")
        println()
        println("                                                 ns/c")
        val c1 = median3 { measureCand1() }
        val c2 = median3 { measureCand2() }
        println("  cand1 callback (cross-module, release LTO)     $c1")
        println("  cand2 list     (cross-module, release LTO)     $c2")
    }

    private fun measureCand1(): Long {
        val buf = makeCand1()
        val ptrs = LongArray(16)
        val lens = IntArray(16)
        try {
            repeat(WARMUP) { iterCand1(buf, ptrs, lens) }
            val mark = TimeSource.Monotonic.markNow()
            repeat(ITERS) { iterCand1(buf, ptrs, lens) }
            return mark.elapsedNow().inWholeNanoseconds / ITERS
        } finally {
            buf.close()
        }
    }

    private fun measureCand2(): Long {
        val buf = makeCand2()
        val ptrs = LongArray(16)
        val lens = IntArray(16)
        try {
            repeat(WARMUP) { iterCand2(buf, ptrs, lens) }
            val mark = TimeSource.Monotonic.markNow()
            repeat(ITERS) { iterCand2(buf, ptrs, lens) }
            return mark.elapsedNow().inWholeNanoseconds / ITERS
        } finally {
            buf.close()
        }
    }

    private fun iterCand1(buf: Cand1IoBuf, ptrs: LongArray, lens: IntArray) {
        var count = 0
        buf.forEachReadableSegment { mem, off, len ->
            ptrs[count] = (mem.asNativePointer() + off)!!.rawValue.toLong()
            lens[count] = len
            count++
        }
        sink += count.toLong()
    }

    private fun iterCand2(buf: Cand2IoBuf, ptrs: LongArray, lens: IntArray) {
        val list = buf.readableSegments()
        val n = list.size
        for (i in 0 until n) {
            val range = list[i]
            ptrs[i] = (range.memory!!.asNativePointer() + range.offset)!!.rawValue.toLong()
            lens[i] = range.length
        }
        sink += n.toLong()
    }

    private fun median3(m: () -> Long): Long {
        val arr = LongArray(TRIALS) { m() }
        arr.sort()
        return arr[TRIALS / 2]
    }

    companion object {
        private const val WARMUP = 500
        private const val ITERS = 10_000
        private const val TRIALS = 5
    }
}
