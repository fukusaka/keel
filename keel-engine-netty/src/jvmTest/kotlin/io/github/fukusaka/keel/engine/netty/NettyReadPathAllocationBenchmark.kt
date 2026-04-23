package io.github.fukusaka.keel.engine.netty

import com.sun.management.ThreadMXBean
import io.github.fukusaka.keel.buf.DirectIoBuf
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.PooledDirectAllocator
import io.github.fukusaka.keel.buf.unsafeBuffer
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import java.lang.management.ManagementFactory
import kotlin.test.Test

/**
 * Measures per-iteration JVM allocation for the two channelRead paths.
 *
 * - **A (baseline)**: `allocator.allocate(cap) + getBytes(ByteBuf → ByteBuffer)`
 * - **B (phase 2)**: `wrapExternal(nioBuffer) + NettyByteBufOwner`
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

    private fun measureA(iterations: Int): Long {
        // Warmup + steady state outside measurement.
        val payload = ByteArray(PAYLOAD) { it.toByte() }
        // Warmup
        for (i in 0 until WARMUP) pathA(payload)

        val start = tmx.getThreadAllocatedBytes(Thread.currentThread().id)
        for (i in 0 until iterations) pathA(payload)
        val end = tmx.getThreadAllocatedBytes(Thread.currentThread().id)
        return (end - start) / iterations
    }

    private fun measureB(iterations: Int): Long {
        val payload = ByteArray(PAYLOAD) { it.toByte() }
        for (i in 0 until WARMUP) pathB(payload)

        val start = tmx.getThreadAllocatedBytes(Thread.currentThread().id)
        for (i in 0 until iterations) pathB(payload)
        val end = tmx.getThreadAllocatedBytes(Thread.currentThread().id)
        return (end - start) / iterations
    }

    /** Mirrors the old channelRead body: allocate + getBytes copy + release. */
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
        // Pipeline consumed — release both refs.
        buf.release()
        byteBuf.release()
    }

    /** Mirrors the new channelRead body: wrap via nioBuffer + NettyByteBufOwner. */
    private fun pathB(payload: ByteArray) {
        val byteBuf: ByteBuf = nettyAlloc.directBuffer(payload.size).writeBytes(payload)
        val readable = byteBuf.readableBytes()
        val nio = byteBuf.nioBuffer(byteBuf.readerIndex(), readable)
        val buf = DirectIoBuf.wrapExternal(
            buffer = nio,
            bytesWritten = readable,
            memoryOwner = NettyByteBufOwner(byteBuf),
        )
        // Pipeline consumed — owner releases byteBuf transitively.
        buf.release()
    }

    @Test
    fun `per-packet allocation A vs B`() {
        val trialsA = LongArray(TRIALS) { measureA(ITERS) }
        val trialsB = LongArray(TRIALS) { measureB(ITERS) }
        trialsA.sort()
        trialsB.sort()
        val medA = trialsA[TRIALS / 2]
        val medB = trialsB[TRIALS / 2]
        val delta = medB - medA

        println("=== NettyReadPath allocation (bytes / packet, payload=${PAYLOAD}B, iters=$ITERS × $TRIALS trials) ===")
        println("  A (alloc+copy)  median=$medA bytes  samples=${trialsA.toList()}")
        println("  B (wrap+owner)  median=$medB bytes  samples=${trialsB.toList()}")
        println("  Δ (B-A)         $delta bytes / packet")
    }

    companion object {
        private const val PAYLOAD = 13 // /hello size
        private const val POOL_CAP = 8192
        private const val WARMUP = 2000
        private const val ITERS = 10_000
        private const val TRIALS = 5
    }
}
