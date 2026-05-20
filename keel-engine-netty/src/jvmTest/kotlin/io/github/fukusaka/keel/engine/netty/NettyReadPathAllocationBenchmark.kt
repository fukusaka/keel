@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.engine.netty

import com.sun.management.ThreadMXBean
import io.github.fukusaka.keel.buf.DirectIoBuf
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.PooledDirectAllocator
import io.github.fukusaka.keel.buf.Segment
import io.github.fukusaka.keel.buf.SegmentOwner
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import io.github.fukusaka.keel.buf.unsafeBuffer
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import java.lang.management.ManagementFactory
import kotlin.test.Test

/**
 * Measures per-iteration JVM allocation for the Netty `channelRead`
 * receive paths.
 *
 * Three variants compared in the same JVM:
 *
 * - **A (copy baseline)**: `allocator.allocate(cap) + ByteBuf.getBytes`.
 *   Hides per-receive object cost via pool reuse — sets the floor for
 *   bytes-per-packet attributable to bookkeeping outside the wrap path.
 * - **B (engine-direct wrap, current)**: [NettyByteBufIoBuf.wrapInbound].
 *   1 [NettyByteBufIoBuf] allocation + 1 cached `nioBuffer` view, no
 *   [Segment] / [SegmentOwner] / `DirectIoBuf` wrapping.
 * - **C (segment-backed wrap, pre-2026-05-20)**: the historical path,
 *   inlined here for A/B confirmation. `DirectIoBuf.wrapExternal` builds
 *   `DirectByteBufferBacking` + `Segment` + `DirectIoBuf`, plus an
 *   ad-hoc [SegmentOwner] that forwards `release` to `ByteBuf.release`
 *   (the role played by the removed `NettyByteBufOwner`).
 *
 * Uses `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` to
 * count current-thread allocation deltas. Excludes the initial warmup
 * and GC happens-to-run noise by taking the median of K trials.
 *
 * Not a unit test — runs as a `@Test` so it executes under the normal
 * `jvmTest` task; inspect stdout for the numbers. Does not assert.
 */
class NettyReadPathAllocationBenchmark {

    private val tmx = ManagementFactory.getThreadMXBean() as ThreadMXBean
    private val nettyAlloc = ByteBufAllocator.DEFAULT
    private val keelAlloc = PooledDirectAllocator().also {
        it.registerPoolSize(POOL_CAP, 16)
    }.createForEventLoop()

    private fun measure(iterations: Int, path: (ByteArray) -> Unit): Long {
        val payload = ByteArray(PAYLOAD) { it.toByte() }
        for (i in 0 until WARMUP) path(payload)

        val start = tmx.getThreadAllocatedBytes(Thread.currentThread().id)
        for (i in 0 until iterations) path(payload)
        val end = tmx.getThreadAllocatedBytes(Thread.currentThread().id)
        return (end - start) / iterations
    }

    /** A: allocate + copy (pool-warm). */
    private fun pathA(payload: ByteArray) {
        val byteBuf: ByteBuf = nettyAlloc.directBuffer(payload.size).writeBytes(payload)
        val readable = byteBuf.readableBytes()
        val cap = maxOf(readable, POOL_CAP)
        val buf: IoBuf = keelAlloc.allocate(cap)
        val bb = buf.unsafeBuffer
        bb.position(buf.writerIndex)
        bb.limit(buf.writerIndex + readable)
        byteBuf.getBytes(byteBuf.readerIndex(), bb)
        buf.writerIndex += readable
        buf.release()
        byteBuf.release()
    }

    /** B: engine-direct wrap via [NettyByteBufIoBuf.wrapInbound] (current production path). */
    private fun pathB(payload: ByteArray) {
        val byteBuf: ByteBuf = nettyAlloc.directBuffer(payload.size).writeBytes(payload)
        val buf = NettyByteBufIoBuf.wrapInbound(byteBuf)
        buf.release()
    }

    /** C: segment-backed wrap via `DirectIoBuf.wrapExternal` + ad-hoc owner (pre-2026-05-20 path). */
    private fun pathC(payload: ByteArray) {
        val byteBuf: ByteBuf = nettyAlloc.directBuffer(payload.size).writeBytes(payload)
        val readable = byteBuf.readableBytes()
        val nio = byteBuf.nioBuffer(byteBuf.readerIndex(), readable)
        val buf = DirectIoBuf.wrapExternal(
            buffer = nio,
            bytesWritten = readable,
            owner = object : SegmentOwner {
                override fun release(segment: Segment) {
                    byteBuf.release()
                }
            },
        )
        buf.release()
    }

    @Test
    fun `per-packet allocation A vs B vs C`() {
        val trialsA = LongArray(TRIALS) { measure(ITERS, ::pathA) }
        val trialsB = LongArray(TRIALS) { measure(ITERS, ::pathB) }
        val trialsC = LongArray(TRIALS) { measure(ITERS, ::pathC) }
        trialsA.sort(); trialsB.sort(); trialsC.sort()
        val medA = trialsA[TRIALS / 2]
        val medB = trialsB[TRIALS / 2]
        val medC = trialsC[TRIALS / 2]

        println("=== NettyReadPath allocation (bytes / packet, payload=${PAYLOAD}B, iters=$ITERS × $TRIALS trials) ===")
        println("  A (alloc+copy, pool-warm)        median=$medA bytes  samples=${trialsA.toList()}")
        println("  B (engine-direct wrap, current)  median=$medB bytes  samples=${trialsB.toList()}")
        println("  C (segment-backed wrap, old)     median=$medC bytes  samples=${trialsC.toList()}")
        println("  Δ (B-C)                          ${medB - medC} bytes / packet (engine-direct vs segment-backed)")
        println("  Δ (B-A)                          ${medB - medA} bytes / packet (wrap vs copy baseline)")
    }

    companion object {
        private const val PAYLOAD = 13 // /hello size
        private const val POOL_CAP = 8192
        private const val WARMUP = 2000
        private const val ITERS = 10_000
        private const val TRIALS = 5
    }
}
