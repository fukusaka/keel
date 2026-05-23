package io.github.fukusaka.keel.buf.poc

import com.sun.management.ThreadMXBean
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.poc.cand1.Cand1AsciiText
import io.github.fukusaka.keel.buf.poc.cand1.Cand1IoBufImpl
import io.github.fukusaka.keel.buf.poc.cand2.Cand2AsciiText
import io.github.fukusaka.keel.buf.poc.cand2.Cand2IoBufImpl
import java.lang.management.ManagementFactory
import kotlin.test.Test

/**
 * In-process microbench for the multi-seg IoBuf PoC.
 *
 * Compares the multi-seg candidates against the existing single-seg
 * `IoBuf` across three scenarios that target the codec hot path:
 *
 * - **scan**: byte-by-byte walk to find an `LF` terminator (mirrors
 *   `BufferedSuspendSource.scanLine`'s inner loop)
 * - **walk**: full byte walk via `getByte` (the parser hot path)
 * - **ascii**: `equals(String)` + `hashCode()` over the multi-seg view
 *   (mirrors `IoBufAsciiText` operations on a multi-seg byte range)
 *
 * Per scenario we run a **single-seg** variant (no chain growth) and a
 * **multi-seg** variant (chain pre-grown to N segments via explicit
 * `appendSegment` calls) — the multi-seg result minus the single-seg
 * result is the per-chain-walk cost the user asked the PoC to measure.
 *
 * **Candidate differentiation note**: cand-1 and cand-2 share the
 * same byte-level read path; the scan / walk / ascii numbers should
 * be near-identical between them. The candidate-specific cost lives
 * in `writeMulti` (engine-side `forEachReadableSegment` callback vs
 * `readableSegments` list iteration) — that path is exercised by the
 * loopback bench, not this in-process one.
 */
class PocMultiSegBenchmark {

    private val tmx = ManagementFactory.getThreadMXBean() as ThreadMXBean

    private val segCap = 512
    private val maxCap = 32 * segCap

    /** "Single segment" scenario: payload fits inside one 512-byte segment. */
    private val singleSegPayload: ByteArray = buildPayload(targetBytes = 384)

    /** "Multi segment" scenario: payload spans 8 segments of 512 bytes. */
    private val multiSegPayload: ByteArray = buildPayload(targetBytes = 8 * segCap - 32)

    /**
     * Builds a payload with an LF byte ~80 % of the way through. The
     * `scan` scenarios search for the first LF; positioning it past
     * the segment boundary in the multi-seg case forces the scanner
     * across at least one segment crossover.
     */
    private fun buildPayload(targetBytes: Int): ByteArray {
        val arr = ByteArray(targetBytes)
        for (i in arr.indices) {
            arr[i] = (('A' + (i % 26)).code).toByte()
        }
        arr[(targetBytes * 4) / 5] = '\n'.code.toByte()
        return arr
    }

    private val asciiLiteral = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(4) // 104 chars

    // ----- buffer factories: pre-fill so writes are not on the hot path -----

    private fun makeBaseline(payload: ByteArray): IoBuf {
        val buf = DefaultAllocator.allocate(payload.size.coerceAtLeast(1))
        buf.writeByteArray(payload, 0, payload.size)
        return buf
    }

    private fun makeCand1(payload: ByteArray, segments: Int): Cand1IoBufImpl {
        val buf = Cand1IoBufImpl(DefaultAllocator, segCap, maxCap)
        repeat(segments - 1) {
            buf.appendSegment(extractSegment(DefaultAllocator.allocate(segCap)))
        }
        buf.writeByteArray(payload, 0, payload.size)
        return buf
    }

    private fun makeCand2(payload: ByteArray, segments: Int): Cand2IoBufImpl {
        val buf = Cand2IoBufImpl(DefaultAllocator, segCap, maxCap)
        repeat(segments - 1) {
            buf.appendSegment(extractSegment(DefaultAllocator.allocate(segCap)))
        }
        buf.writeByteArray(payload, 0, payload.size)
        return buf
    }

    // ----- timing harness (same shape as HttpRequestParseAllocBenchmark) -----

    @Suppress("unused")
    private var sink: Long = 0

    private fun measure(iters: Int, body: () -> Unit): Pair<Long, Long> {
        repeat(WARMUP) { body() }
        val tid = Thread.currentThread().threadId()
        val startAlloc = tmx.getThreadAllocatedBytes(tid)
        val startNs = System.nanoTime()
        repeat(iters) { body() }
        val endNs = System.nanoTime()
        val endAlloc = tmx.getThreadAllocatedBytes(tid)
        return (endAlloc - startAlloc) / iters to (endNs - startNs) / iters
    }

    private fun median3(m: () -> Pair<Long, Long>): Pair<Long, Long> {
        val results = List(TRIALS) { m() }
        val sortedByTime = results.sortedBy { it.second }
        return sortedByTime[TRIALS / 2]
    }

    // ----- scenarios -----

    @Test
    fun `report multi-seg IoBuf PoC microbench`() {
        println("=== PoC multi-seg IoBuf microbench (iters=$ITERS x $TRIALS, JVM) ===")
        println("    scenarios run on single-seg (1 segment) and multi-seg (8 segments)")
        println()
        println("                                       bytes/cycle    ns/cycle")
        runScan()
        runWalk()
        runAscii()
    }

    private fun runScan() {
        // scan-line: find first LF starting from readerIndex 0
        val (baseAlloc, baseNs) = median3 {
            val buf = makeBaseline(singleSegPayload)
            try {
                measure(ITERS) {
                    val cap = buf.capacity
                    for (i in 0 until cap) {
                        if (buf.getByte(i).toInt() == 0x0A) {
                            sink += i.toLong()
                            return@measure
                        }
                    }
                }
            } finally {
                buf.release()
            }
        }
        val (c1ssAlloc, c1ssNs) = median3 {
            val buf = makeCand1(singleSegPayload, 1)
            try {
                measure(ITERS) { scanCand1(buf) }
            } finally {
                buf.close()
            }
        }
        val (c2ssAlloc, c2ssNs) = median3 {
            val buf = makeCand2(singleSegPayload, 1)
            try {
                measure(ITERS) { scanCand2(buf) }
            } finally {
                buf.close()
            }
        }
        val (c1msAlloc, c1msNs) = median3 {
            val buf = makeCand1(multiSegPayload, 8)
            try {
                measure(ITERS) { scanCand1(buf) }
            } finally {
                buf.close()
            }
        }
        val (c2msAlloc, c2msNs) = median3 {
            val buf = makeCand2(multiSegPayload, 8)
            try {
                measure(ITERS) { scanCand2(buf) }
            } finally {
                buf.close()
            }
        }
        println("  scan baseline                         %5d        %6d".format(baseAlloc, baseNs))
        println("  scan cand1 (single-seg)               %5d        %6d".format(c1ssAlloc, c1ssNs))
        println("  scan cand2 (single-seg)               %5d        %6d".format(c2ssAlloc, c2ssNs))
        println("  scan cand1 (8-seg)                    %5d        %6d".format(c1msAlloc, c1msNs))
        println("  scan cand2 (8-seg)                    %5d        %6d".format(c2msAlloc, c2msNs))
    }

    private fun scanCand1(buf: Cand1IoBufImpl) {
        val cap = buf.capacity
        for (i in 0 until cap) {
            if (buf.getByte(i).toInt() == 0x0A) {
                sink += i.toLong()
                return
            }
        }
    }

    private fun scanCand2(buf: Cand2IoBufImpl) {
        val cap = buf.capacity
        for (i in 0 until cap) {
            if (buf.getByte(i).toInt() == 0x0A) {
                sink += i.toLong()
                return
            }
        }
    }

    private fun runWalk() {
        val (baseAlloc, baseNs) = median3 {
            val buf = makeBaseline(singleSegPayload)
            try {
                measure(ITERS) {
                    var acc = 0L
                    val cap = buf.capacity
                    for (i in 0 until cap) acc += buf.getByte(i).toLong()
                    sink += acc
                }
            } finally {
                buf.release()
            }
        }
        val (c1ssAlloc, c1ssNs) = median3 {
            val buf = makeCand1(singleSegPayload, 1)
            try {
                measure(ITERS) { walkCand1(buf) }
            } finally {
                buf.close()
            }
        }
        val (c2ssAlloc, c2ssNs) = median3 {
            val buf = makeCand2(singleSegPayload, 1)
            try {
                measure(ITERS) { walkCand2(buf) }
            } finally {
                buf.close()
            }
        }
        val (c1msAlloc, c1msNs) = median3 {
            val buf = makeCand1(multiSegPayload, 8)
            try {
                measure(ITERS) { walkCand1(buf) }
            } finally {
                buf.close()
            }
        }
        val (c2msAlloc, c2msNs) = median3 {
            val buf = makeCand2(multiSegPayload, 8)
            try {
                measure(ITERS) { walkCand2(buf) }
            } finally {
                buf.close()
            }
        }
        println("  walk baseline                         %5d        %6d".format(baseAlloc, baseNs))
        println("  walk cand1 (single-seg)               %5d        %6d".format(c1ssAlloc, c1ssNs))
        println("  walk cand2 (single-seg)               %5d        %6d".format(c2ssAlloc, c2ssNs))
        println("  walk cand1 (8-seg)                    %5d        %6d".format(c1msAlloc, c1msNs))
        println("  walk cand2 (8-seg)                    %5d        %6d".format(c2msAlloc, c2msNs))
    }

    private fun walkCand1(buf: Cand1IoBufImpl) {
        var acc = 0L
        val cap = buf.capacity
        for (i in 0 until cap) acc += buf.getByte(i).toLong()
        sink += acc
    }

    private fun walkCand2(buf: Cand2IoBufImpl) {
        var acc = 0L
        val cap = buf.capacity
        for (i in 0 until cap) acc += buf.getByte(i).toLong()
        sink += acc
    }

    private fun runAscii() {
        val literal = asciiLiteral
        val (c1ssAlloc, c1ssNs) = median3 {
            val buf = makeCand1(literal.encodeToByteArray(), 1)
            val view = Cand1AsciiText(buf, 0, literal.length)
            try {
                measure(ITERS) {
                    sink += view.hashCode().toLong()
                    if (view.contentEquals(literal)) sink++
                }
            } finally {
                buf.close()
            }
        }
        val (c2ssAlloc, c2ssNs) = median3 {
            val buf = makeCand2(literal.encodeToByteArray(), 1)
            val view = Cand2AsciiText(buf, 0, literal.length)
            try {
                measure(ITERS) {
                    sink += view.hashCode().toLong()
                    if (view.contentEquals(literal)) sink++
                }
            } finally {
                buf.close()
            }
        }
        // Multi-seg ascii: spread the literal across 4 segments (=128/seg).
        val (c1msAlloc, c1msNs) = median3 {
            val buf = makeCand1(literal.encodeToByteArray(), 4)
            val view = Cand1AsciiText(buf, 0, literal.length)
            try {
                measure(ITERS) {
                    sink += view.hashCode().toLong()
                    if (view.contentEquals(literal)) sink++
                }
            } finally {
                buf.close()
            }
        }
        val (c2msAlloc, c2msNs) = median3 {
            val buf = makeCand2(literal.encodeToByteArray(), 4)
            val view = Cand2AsciiText(buf, 0, literal.length)
            try {
                measure(ITERS) {
                    sink += view.hashCode().toLong()
                    if (view.contentEquals(literal)) sink++
                }
            } finally {
                buf.close()
            }
        }
        println("  ascii cand1 (single-seg)              %5d        %6d".format(c1ssAlloc, c1ssNs))
        println("  ascii cand2 (single-seg)              %5d        %6d".format(c2ssAlloc, c2ssNs))
        println("  ascii cand1 (4-seg, hash cached)      %5d        %6d".format(c1msAlloc, c1msNs))
        println("  ascii cand2 (4-seg, hash cached)      %5d        %6d".format(c2msAlloc, c2msNs))
    }

    companion object {
        private const val WARMUP = 1_000
        private const val ITERS = 20_000
        private const val TRIALS = 5
    }
}
