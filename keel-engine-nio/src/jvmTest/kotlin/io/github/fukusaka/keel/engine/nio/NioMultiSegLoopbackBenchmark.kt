package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.core.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Loopback throughput report for the NIO multi-seg gather path,
 * comparing single-seg vs multi-seg `IoBuf` writes under real socket
 * I/O. Reports `ns/write` (median across 5 trials) for three shapes:
 *
 *   - **single-seg / 4 KiB**: one [DefaultAllocator.allocate]-d buffer
 *     per write (the existing engine path; `flushSingle` fast path).
 *   - **multi-seg / 4 × 1 KiB**: a 4 KiB chain split into 4 segments
 *     via [IoBuf.appendSegment]; exercises the gather path through 4
 *     `ByteBuffer` entries in `SocketChannel.write(ByteBuffer[])`.
 *   - **multi-seg / 8 × 512 B**: doubles the chain depth at the same
 *     payload size.
 *
 * Output is `println`-formatted and visible only on `--info` test
 * output; the test deliberately does not assert specific ns/write
 * thresholds because absolute values depend on the host. The bench
 * exists to detect future regressions in the multi-seg overhead vs
 * the PoC-predicted ~14 % envelope (PR #602 closed) and to anchor the
 * other engine integrations in PR-3b / PR-3c on the same shape.
 */
class NioMultiSegLoopbackBenchmark {

    // Use a generous bench-specific timeout (60 s) rather than the
    // category-default 10 s — JIT warmup + 5 trials × ITERS write/flush
    // round-trips can legitimately exceed the integration-test
    // [TEST_TIMEOUT] under CI / aggregate-suite load.
    @Test
    fun `report loopback throughput singleSeg vs multiSeg`() = runBlocking { withTimeout(60.seconds) {
        val engine = NioEngine()
        val server = engine.bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        // Drain thread to consume bytes off the client socket; without
        // it the kernel sndbuf fills and write throughput becomes
        // dominated by syscall blocking rather than the write path.
        val drainThread = Thread {
            val drainBuf = ByteArray(64 * 1024)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val n = client.getInputStream().read(drainBuf)
                    if (n <= 0) break
                }
            } catch (e: Throwable) {
                // Connection close on test teardown — expected.
            }
        }.also { it.isDaemon = true; it.start() }

        try {
            println("=== NIO multi-seg loopback (engine-nio) ===")
            println("                                         ns/write")

            val ns1 = median3Suspend { measureWrites(ch) { newSingleSeg(PAYLOAD) } }
            println("  single-seg 1 x ${PAYLOAD} B                 $ns1")

            val ns4 = median3Suspend { measureWrites(ch) { newMultiSeg(PAYLOAD / 4, 4) } }
            println("  multi-seg  4 x ${PAYLOAD / 4} B                  $ns4")

            val ns8 = median3Suspend { measureWrites(ch) { newMultiSeg(PAYLOAD / 8, 8) } }
            println("  multi-seg  8 x ${PAYLOAD / 8} B                  $ns8")
        } finally {
            ch.close()
            client.close()
            drainThread.interrupt()
            server.close()
            engine.close()
        }
    } }

    private suspend fun measureWrites(ch: io.github.fukusaka.keel.pipeline.PipelinedChannel, makeBuf: () -> IoBuf): Long {
        // Warmup: prime the JIT.
        repeat(WARMUP) {
            ch.write(makeBuf())
            ch.flush()
        }
        val mark = TimeSource.Monotonic.markNow()
        repeat(ITERS) {
            ch.write(makeBuf())
            ch.flush()
        }
        return mark.elapsedNow().inWholeNanoseconds / ITERS
    }

    private fun newSingleSeg(size: Int): IoBuf {
        val buf = DefaultAllocator.allocate(size)
        for (i in 0 until size) buf.writeByte((i and 0xFF).toByte())
        return buf
    }

    private fun newMultiSeg(segSize: Int, segCount: Int): IoBuf {
        val total = segSize * segCount
        val buf = DefaultAllocator.allocate(capacity = segSize, maxCapacity = total)
        repeat(segCount - 1) { buf.appendSegment(DefaultAllocator.allocateSegment(segSize)) }
        for (i in 0 until total) buf.writeByte((i and 0xFF).toByte())
        return buf
    }

    private suspend fun median3Suspend(m: suspend () -> Long): Long {
        val arr = LongArray(TRIALS)
        for (i in 0 until TRIALS) arr[i] = m()
        arr.sort()
        return arr[TRIALS / 2]
    }

    companion object {
        private const val PAYLOAD = 4096
        private const val WARMUP = 500
        private const val ITERS = 5_000
        private const val TRIALS = 5
    }
}
