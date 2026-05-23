package io.github.fukusaka.keel.buf.poc

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.buf.asNativePointer
import io.github.fukusaka.keel.buf.poc.cand1.Cand1AsciiText
import io.github.fukusaka.keel.buf.poc.cand1.Cand1IoBuf
import io.github.fukusaka.keel.buf.poc.cand1.Cand1IoBufImpl
import io.github.fukusaka.keel.buf.poc.cand1.SegmentRangeAction
import io.github.fukusaka.keel.buf.poc.cand2.Cand2AsciiText
import io.github.fukusaka.keel.buf.poc.cand2.Cand2IoBuf
import io.github.fukusaka.keel.buf.poc.cand2.Cand2IoBufImpl
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.plus
import kotlin.test.Test
import kotlin.time.measureTime

/**
 * Native-side counterpart to `PocMultiSegBenchmark`.
 *
 * Same scenarios (scan / walk / ascii / iov-build) but the timing
 * harness is `measureNanoTime` since Native does not have a
 * `ThreadMXBean` equivalent. Allocation counts come from
 * [TrackingAllocator] on the iov-build scenario (the only one where
 * a lambda allocation differs between candidates); the other
 * scenarios share the byte-level path between cand-1 and cand-2 so
 * alloc-count parity is uninteresting.
 *
 * Runs on whichever Native target the host can compile (macosArm64
 * here in keel's `applyDefaultHierarchyTemplate` setup; linuxX64
 * follows the same hierarchy on luna).
 */
@OptIn(ExperimentalForeignApi::class)
class PocMultiSegNativeBenchmark {

    private val segCap = 512
    private val maxCap = 32 * segCap
    private val singleSegPayload: ByteArray = buildPayload(targetBytes = 384)
    private val multiSegPayload: ByteArray = buildPayload(targetBytes = 8 * segCap - 32)
    private val asciiLiteral = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(4)

    private fun buildPayload(targetBytes: Int): ByteArray {
        val arr = ByteArray(targetBytes)
        for (i in arr.indices) arr[i] = (('A' + (i % 26)).code).toByte()
        arr[(targetBytes * 4) / 5] = '\n'.code.toByte()
        return arr
    }

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

    @Suppress("unused")
    private var sink: Long = 0

    private fun median3(m: () -> Long): Long {
        val arr = LongArray(TRIALS) { m() }
        arr.sort()
        return arr[TRIALS / 2]
    }

    private fun runBlock(iters: Int, body: () -> Unit): Long {
        repeat(WARMUP) { body() }
        val elapsed = measureTime { repeat(iters) { body() } }
        return elapsed.inWholeNanoseconds / iters
    }

    @Test
    fun `report multi-seg IoBuf PoC microbench native`() {
        println("=== PoC multi-seg IoBuf microbench (iters=$ITERS x $TRIALS, Native) ===")
        println()
        println("                                                ns/cycle")
        runScan()
        runWalk()
        runAscii()
        runIovBuild()
        runIovBuildInterfaceMonomorphic()
        runIovBuildInterfaceMegamorphic()
    }

    private fun runScan() {
        val base = median3 {
            val buf = makeBaseline(singleSegPayload)
            try {
                runBlock(ITERS) {
                    val cap = buf.capacity
                    for (i in 0 until cap) {
                        if (buf.getByte(i).toInt() == 0x0A) {
                            sink += i.toLong()
                            return@runBlock
                        }
                    }
                }
            } finally {
                buf.release()
            }
        }
        val c1ss = median3 {
            val buf = makeCand1(singleSegPayload, 1)
            try {
                runBlock(ITERS) { scanCand1(buf) }
            } finally {
                buf.close()
            }
        }
        val c2ss = median3 {
            val buf = makeCand2(singleSegPayload, 1)
            try {
                runBlock(ITERS) { scanCand2(buf) }
            } finally {
                buf.close()
            }
        }
        val c1ms = median3 {
            val buf = makeCand1(multiSegPayload, 8)
            try {
                runBlock(ITERS) { scanCand1(buf) }
            } finally {
                buf.close()
            }
        }
        val c2ms = median3 {
            val buf = makeCand2(multiSegPayload, 8)
            try {
                runBlock(ITERS) { scanCand2(buf) }
            } finally {
                buf.close()
            }
        }
        println("  scan baseline                                  $base")
        println("  scan cand1 (single-seg)                        $c1ss")
        println("  scan cand2 (single-seg)                        $c2ss")
        println("  scan cand1 (8-seg)                             $c1ms")
        println("  scan cand2 (8-seg)                             $c2ms")
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
        val base = median3 {
            val buf = makeBaseline(singleSegPayload)
            try {
                runBlock(ITERS) {
                    var acc = 0L
                    val cap = buf.capacity
                    for (i in 0 until cap) acc += buf.getByte(i).toLong()
                    sink += acc
                }
            } finally {
                buf.release()
            }
        }
        val c1ss = median3 {
            val buf = makeCand1(singleSegPayload, 1)
            try {
                runBlock(ITERS) {
                    var acc = 0L
                    val cap = buf.capacity
                    for (i in 0 until cap) acc += buf.getByte(i).toLong()
                    sink += acc
                }
            } finally {
                buf.close()
            }
        }
        val c1ms = median3 {
            val buf = makeCand1(multiSegPayload, 8)
            try {
                runBlock(ITERS) {
                    var acc = 0L
                    val cap = buf.capacity
                    for (i in 0 until cap) acc += buf.getByte(i).toLong()
                    sink += acc
                }
            } finally {
                buf.close()
            }
        }
        val c2ss = median3 {
            val buf = makeCand2(singleSegPayload, 1)
            try {
                runBlock(ITERS) {
                    var acc = 0L
                    val cap = buf.capacity
                    for (i in 0 until cap) acc += buf.getByte(i).toLong()
                    sink += acc
                }
            } finally {
                buf.close()
            }
        }
        val c2ms = median3 {
            val buf = makeCand2(multiSegPayload, 8)
            try {
                runBlock(ITERS) {
                    var acc = 0L
                    val cap = buf.capacity
                    for (i in 0 until cap) acc += buf.getByte(i).toLong()
                    sink += acc
                }
            } finally {
                buf.close()
            }
        }
        println("  walk baseline                                  $base")
        println("  walk cand1 (single-seg)                        $c1ss")
        println("  walk cand2 (single-seg)                        $c2ss")
        println("  walk cand1 (8-seg)                             $c1ms")
        println("  walk cand2 (8-seg)                             $c2ms")
    }

    private fun runAscii() {
        val c1ss = median3 {
            val buf = makeCand1(asciiLiteral.encodeToByteArray(), 1)
            val view = Cand1AsciiText(buf, 0, asciiLiteral.length)
            try {
                runBlock(ITERS) {
                    sink += view.hashCode().toLong()
                    if (view.contentEquals(asciiLiteral)) sink++
                }
            } finally {
                buf.close()
            }
        }
        val c2ss = median3 {
            val buf = makeCand2(asciiLiteral.encodeToByteArray(), 1)
            val view = Cand2AsciiText(buf, 0, asciiLiteral.length)
            try {
                runBlock(ITERS) {
                    sink += view.hashCode().toLong()
                    if (view.contentEquals(asciiLiteral)) sink++
                }
            } finally {
                buf.close()
            }
        }
        val c1ms = median3 {
            val buf = makeCand1(asciiLiteral.encodeToByteArray(), 4)
            val view = Cand1AsciiText(buf, 0, asciiLiteral.length)
            try {
                runBlock(ITERS) {
                    sink += view.hashCode().toLong()
                    if (view.contentEquals(asciiLiteral)) sink++
                }
            } finally {
                buf.close()
            }
        }
        val c2ms = median3 {
            val buf = makeCand2(asciiLiteral.encodeToByteArray(), 4)
            val view = Cand2AsciiText(buf, 0, asciiLiteral.length)
            try {
                runBlock(ITERS) {
                    sink += view.hashCode().toLong()
                    if (view.contentEquals(asciiLiteral)) sink++
                }
            } finally {
                buf.close()
            }
        }
        println("  ascii cand1 (single-seg)                       $c1ss")
        println("  ascii cand2 (single-seg)                       $c2ss")
        println("  ascii cand1 (4-seg, hash cached)               $c1ms")
        println("  ascii cand2 (4-seg, hash cached)               $c2ms")
    }

    /**
     * Native-side iov-build cost — same shape as the JVM bench but
     * `asNativePointer()` + raw pointer arithmetic in place of
     * `asByteBuffer().duplicate()`. `TrackingAllocator` counts the
     * per-iteration allocations (callback object on cand-1 vs the
     * shared SegmentRangeList instance on cand-2).
     */
    private fun runIovBuild() {
        val c1 = median3 {
            val tracker = TrackingAllocator(DefaultAllocator)
            val buf = Cand1IoBufImpl(tracker, segCap, maxCap)
            repeat(7) { buf.appendSegment(extractSegment(tracker.allocate(segCap))) }
            buf.writeByteArray(multiSegPayload, 0, multiSegPayload.size)
            val ptrs = LongArray(16)
            val lens = IntArray(16)
            val baseline = tracker.allocateCount
            val ns = try {
                runBlock(ITERS) {
                    var count = 0
                    buf.forEachReadableSegment { mem, off, len ->
                        ptrs[count] = (mem.asNativePointer() + off)!!.rawValue.toLong()
                        lens[count] = len
                        count++
                    }
                    sink += count.toLong()
                }
            } finally {
                buf.close()
            }
            val deltaAlloc = tracker.allocateCount - baseline
            println("    cand1 iov-build allocator events delta: $deltaAlloc")
            ns
        }
        val c2 = median3 {
            val tracker = TrackingAllocator(DefaultAllocator)
            val buf = Cand2IoBufImpl(tracker, segCap, maxCap)
            repeat(7) { buf.appendSegment(extractSegment(tracker.allocate(segCap))) }
            buf.writeByteArray(multiSegPayload, 0, multiSegPayload.size)
            val ptrs = LongArray(16)
            val lens = IntArray(16)
            val baseline = tracker.allocateCount
            val ns = try {
                runBlock(ITERS) {
                    val list = buf.readableSegments()
                    val n = list.size
                    for (i in 0 until n) {
                        val range = list[i]
                        ptrs[i] = (range.memory!!.asNativePointer() + range.offset)!!.rawValue.toLong()
                        lens[i] = range.length
                    }
                    sink += n.toLong()
                }
            } finally {
                buf.close()
            }
            val deltaAlloc = tracker.allocateCount - baseline
            println("    cand2 iov-build allocator events delta: $deltaAlloc")
            ns
        }
        println("  iov-build cand1 (8-seg, callback)              $c1")
        println("  iov-build cand2 (8-seg, list)                  $c2")
    }

    /**
     * Condition (1): interface-typed receiver, but only one impl seen
     * at the indirect call site. Kotlin/Native LTO should still be
     * able to devirtualise + inline because the whole program contains
     * a single concrete `Cand1IoBuf` impl reachable from this call
     * site.
     *
     * If the numbers match [runIovBuild], the AOT compiler is robust
     * against the static type widening (good). If they diverge, the
     * concrete-type fast path in the original bench was effectively
     * an artefact of the variable's declared type — production code
     * that passes the buffer through `Cand1IoBuf` interface boundaries
     * would degrade.
     */
    private fun runIovBuildInterfaceMonomorphic() {
        val c1Ns = median3 {
            val buf: Cand1IoBuf = makeCand1(multiSegPayload, 8)
            val ptrs = LongArray(16)
            val lens = IntArray(16)
            try {
                runBlock(ITERS) {
                    var count = 0
                    invokeCand1ForEach(buf) { mem, off, len ->
                        ptrs[count] = (mem.asNativePointer() + off)!!.rawValue.toLong()
                        lens[count] = len
                        count++
                    }
                    sink += count.toLong()
                }
            } finally {
                buf.close()
            }
        }
        val c2Ns = median3 {
            val buf: Cand2IoBuf = makeCand2(multiSegPayload, 8)
            val ptrs = LongArray(16)
            val lens = IntArray(16)
            try {
                runBlock(ITERS) {
                    val list = invokeCand2ReadableSegments(buf)
                    val n = list.size
                    for (i in 0 until n) {
                        val range = list[i]
                        ptrs[i] = (range.memory!!.asNativePointer() + range.offset)!!.rawValue.toLong()
                        lens[i] = range.length
                    }
                    sink += n.toLong()
                }
            } finally {
                buf.close()
            }
        }
        println("  iov-build cand1 (interface, monomorphic)       $c1Ns")
        println("  iov-build cand2 (interface, monomorphic)       $c2Ns")
    }

    /**
     * Condition (2): force the indirect call site to be megamorphic
     * by also passing the stub impl through it during warm-up — so
     * Kotlin/Native LTO sees two concrete types reaching the same
     * call site and cannot pick one to inline.
     *
     * This is the **worst case** for the cand1 callback path on
     * Native: every `forEachReadableSegment` invocation goes through
     * a virtual call with no inlining of the SAM lambda, no inlining
     * of the body. Same situation for the cand2 list path's
     * `readableSegments()`. The comparison tells whether the JVM
     * baseline (~165 / 152 ns) is closer to the Native worst case
     * than to the Native best case.
     */
    private fun runIovBuildInterfaceMegamorphic() {
        val stub1: Cand1IoBuf = Cand1IoBufStubImpl()
        val stub2: Cand2IoBuf = Cand2IoBufStubImpl()
        val c1Ns = median3 {
            val real: Cand1IoBuf = makeCand1(multiSegPayload, 8)
            val ptrs = LongArray(16)
            val lens = IntArray(16)
            try {
                // Pollute the call site by routing the stub through
                // the same helper before measuring. The helper's
                // single call site now resolves to both
                // Cand1IoBufImpl and Cand1IoBufStubImpl, which forces
                // a virtual dispatch.
                repeat(WARMUP) {
                    invokeCand1ForEach(stub1) { _, _, _ -> /* no-op */ }
                    invokeCand1ForEach(real) { _, _, _ -> /* no-op */ }
                }
                val mark = kotlin.time.TimeSource.Monotonic.markNow()
                repeat(ITERS) {
                    var count = 0
                    invokeCand1ForEach(real) { mem, off, len ->
                        ptrs[count] = (mem.asNativePointer() + off)!!.rawValue.toLong()
                        lens[count] = len
                        count++
                    }
                    sink += count.toLong()
                }
                mark.elapsedNow().inWholeNanoseconds / ITERS
            } finally {
                real.close()
            }
        }
        val c2Ns = median3 {
            val real: Cand2IoBuf = makeCand2(multiSegPayload, 8)
            val ptrs = LongArray(16)
            val lens = IntArray(16)
            try {
                repeat(WARMUP) {
                    invokeCand2ReadableSegments(stub2)
                    invokeCand2ReadableSegments(real)
                }
                val mark = kotlin.time.TimeSource.Monotonic.markNow()
                repeat(ITERS) {
                    val list = invokeCand2ReadableSegments(real)
                    val n = list.size
                    for (i in 0 until n) {
                        val range = list[i]
                        ptrs[i] = (range.memory!!.asNativePointer() + range.offset)!!.rawValue.toLong()
                        lens[i] = range.length
                    }
                    sink += n.toLong()
                }
                mark.elapsedNow().inWholeNanoseconds / ITERS
            } finally {
                real.close()
            }
        }
        println("  iov-build cand1 (interface, megamorphic)       $c1Ns")
        println("  iov-build cand2 (interface, megamorphic)       $c2Ns")
    }

    /**
     * Dispatch helper that takes a [Cand1IoBuf] interface argument.
     * Having a single helper that is called from both `monomorphic`
     * and `megamorphic` scenarios with different concrete impls is
     * what makes the latter actually megamorphic — the AOT compiler
     * sees both [Cand1IoBufImpl] and [Cand1IoBufStubImpl] reach
     * `buf.forEachReadableSegment` here and can no longer pick one to
     * specialise on.
     */
    private fun invokeCand1ForEach(buf: Cand1IoBuf, action: SegmentRangeAction) {
        buf.forEachReadableSegment(action)
    }

    private fun invokeCand2ReadableSegments(buf: Cand2IoBuf): io.github.fukusaka.keel.buf.poc.cand2.SegmentRangeList =
        buf.readableSegments()

    companion object {
        private const val WARMUP = 500
        private const val ITERS = 10_000
        private const val TRIALS = 5
    }
}
