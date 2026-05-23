package io.github.fukusaka.keel.engine.nio.poc

import com.sun.management.ThreadMXBean
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.asByteBuffer
import io.github.fukusaka.keel.buf.poc.appendNewSegment
import io.github.fukusaka.keel.buf.poc.cand1.Cand1IoBuf
import io.github.fukusaka.keel.buf.poc.cand1.Cand1IoBufImpl
import io.github.fukusaka.keel.buf.poc.cand2.Cand2IoBuf
import io.github.fukusaka.keel.buf.poc.cand2.Cand2IoBufImpl
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import kotlin.test.Test

/**
 * Cross-module variant of `PocMultiSegBenchmark.runIovBuildWarmupSweep`.
 *
 * The PoC iov-build bench in keel-io's jvmTest measures the candidate
 * delta when caller and callee live in the same compilation unit —
 * HotSpot can inline through the SAM lambda dispatch with full module
 * visibility and the candidate gap there represents an upper bound on
 * cand2's advantage.
 *
 * This bench replicates the same measurement from
 * `keel-engine-nio`'s jvmTest — a different module / different
 * compilation unit. If HotSpot can still devirtualise + inline the
 * `Cand1IoBuf.forEachReadableSegment` callback across the module
 * boundary (typical for jar-on-classpath classloading), the numbers
 * should match the in-module bench. If not — for instance if profile
 * pollution by other call sites prevents the inline cache from
 * stabilising — cand1's gap widens, and the in-module measurement
 * understates the production cost.
 */
class PocMultiSegCrossModuleBenchmark {

    private val tmx = ManagementFactory.getThreadMXBean() as ThreadMXBean
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
    fun `report cross-module iov-build warmup sweep`() {
        println("=== PoC multi-seg IoBuf cross-module iov-build (JVM, engine-nio) ===")
        println("    8 segments x 512 B = 4064 B / cycle (payload size)")
        println()
        println("                                                      bytes/c    ns/c")
        for (warmup in intArrayOf(1_000, 50_000, 200_000)) {
            val c1 = measureCand1(warmup)
            val c2 = measureCand2(warmup)
            println(
                "  warmup=%6d cand1 callback / cand2 list:    %4d / %4d   %4d / %4d".format(
                    warmup,
                    c1.first,
                    c2.first,
                    c1.second,
                    c2.second,
                ),
            )
        }
    }

    private fun measureCand1(warmupIters: Int): Pair<Long, Long> {
        val buf = makeCand1()
        val iovs = arrayOfNulls<ByteBuffer>(16)
        try {
            repeat(warmupIters) { iterCand1(buf, iovs) }
            val tid = Thread.currentThread().threadId()
            val aStart = tmx.getThreadAllocatedBytes(tid)
            val nStart = System.nanoTime()
            repeat(ITERS) { iterCand1(buf, iovs) }
            val nEnd = System.nanoTime()
            val aEnd = tmx.getThreadAllocatedBytes(tid)
            return (aEnd - aStart) / ITERS to (nEnd - nStart) / ITERS
        } finally {
            buf.close()
        }
    }

    private fun measureCand2(warmupIters: Int): Pair<Long, Long> {
        val buf = makeCand2()
        val iovs = arrayOfNulls<ByteBuffer>(16)
        try {
            repeat(warmupIters) { iterCand2(buf, iovs) }
            val tid = Thread.currentThread().threadId()
            val aStart = tmx.getThreadAllocatedBytes(tid)
            val nStart = System.nanoTime()
            repeat(ITERS) { iterCand2(buf, iovs) }
            val nEnd = System.nanoTime()
            val aEnd = tmx.getThreadAllocatedBytes(tid)
            return (aEnd - aStart) / ITERS to (nEnd - nStart) / ITERS
        } finally {
            buf.close()
        }
    }

    private fun iterCand1(buf: Cand1IoBuf, iovs: Array<ByteBuffer?>) {
        var count = 0
        buf.forEachReadableSegment { mem, off, len ->
            val bb = mem.asByteBuffer().duplicate()
            bb.position(off)
            bb.limit(off + len)
            iovs[count] = bb
            count++
        }
        sink += count.toLong()
    }

    private fun iterCand2(buf: Cand2IoBuf, iovs: Array<ByteBuffer?>) {
        val list = buf.readableSegments()
        val n = list.size
        for (i in 0 until n) {
            val range = list[i]
            val bb = range.memory!!.asByteBuffer().duplicate()
            bb.position(range.offset)
            bb.limit(range.offset + range.length)
            iovs[i] = bb
        }
        sink += n.toLong()
    }

    companion object {
        private const val ITERS = 20_000
    }
}
