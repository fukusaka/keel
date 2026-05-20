package io.github.fukusaka.keel.server.websocket

import com.sun.management.ThreadMXBean
import io.github.fukusaka.keel.codec.websocket.WsFrame
import io.github.fukusaka.keel.testing.websocket.WsSeamContext
import java.lang.management.ManagementFactory
import kotlin.test.Test

/**
 * Measures per-frame JVM allocation for the keel WebSocket hot path
 * (post-upgrade frame decode → echo → frame encode).
 *
 * Drives the [WsSeamContext]'s pre-wired pipeline (`WsFrameEncoder` /
 * `WsFrameDecoder` / `WsEchoHandler(postUpgradeMode = true)`) over a
 * `TestIoTransport`. Each iteration feeds one pre-encoded frame via
 * `notifyRead` and releases the echoed reply, so the
 * `ThreadMXBean.getThreadAllocatedBytes` delta captures the entire
 * decode → echo → encode round-trip for one frame.
 *
 * Five scenarios cover the dominant frame shapes that show up in
 * production WS workloads:
 *
 * - **A (text frame, 13B payload)**: minimal text frame — the WS
 *   analogue of the HTTP `/hello` scenario in
 *   [io.github.fukusaka.keel.server.http.HttpServerHotPathAllocationBenchmark].
 * - **B (text frame, 1 KB payload)**: medium-size message — the per-frame
 *   alloc that survives once the codec is steady-state.
 * - **C (binary frame, 1 KB payload)**: binary-opcode counterpart to B.
 *   Allocation should match B (the codec treats text/binary identically
 *   on the wire path), this scenario just confirms it.
 * - **D (binary frame, 64 KB payload)**: large frame — varies the
 *   per-frame byte cost without changing the per-frame object count, so
 *   the alloc delta vs B isolates byte-array sizing from object
 *   bookkeeping.
 * - **E (10 × text 13B frames, pipelined)**: a single inbound buffer
 *   carrying 10 back-to-back text frames, simulating a chatty client.
 *   Reported as per-frame bytes for direct comparison with A.
 *
 * Uses `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` (same
 * primitive as
 * [io.github.fukusaka.keel.server.http.HttpServerHotPathAllocationBenchmark]
 * and `:keel-engine-netty:NettyReadPathAllocationBenchmark`).
 *
 * Not a unit test — runs as a `@Test` under the normal `jvmTest` task;
 * inspect stdout. Does not assert.
 */
class WsHotPathAllocationBenchmark {

    private val tmx = ManagementFactory.getThreadMXBean() as ThreadMXBean

    private fun measure(iterations: Int, scenario: Scenario): Long {
        repeat(WARMUP) { scenario.runOnce() }
        val start = tmx.getThreadAllocatedBytes(Thread.currentThread().id)
        repeat(iterations) { scenario.runOnce() }
        val end = tmx.getThreadAllocatedBytes(Thread.currentThread().id)
        return (end - start) / iterations
    }

    /**
     * A scenario sets up a WS pipeline once and exposes [runOnce] that
     * feeds one frame round-trip, discarding the echoed reply.
     *
     * Each scenario carries its own [WsSeamContext] so test state stays
     * isolated. The pre-encoded frame bytes are reused across iterations
     * (encoded once at construction) so the measurement does not include
     * the encoder's allocation.
     */
    private abstract class Scenario {
        val ctx: WsSeamContext = WsSeamContext.new(label = "alloc-bench")

        /** Wire-encoded frame bytes — fed unchanged on each iteration. */
        protected abstract val frameBytes: ByteArray

        protected fun feedAndDrain() {
            val buf = ctx.tracker.allocate(frameBytes.size)
            buf.writeByteArray(frameBytes, 0, frameBytes.size)
            ctx.channel.pipeline.notifyRead(buf)
            // The echo handler writes a frame back; drop every captured
            // outbound buffer so the next iteration starts fresh.
            ctx.transport.releaseWritten()
        }

        abstract fun runOnce()
    }

    private inner class TextFrame(payloadSize: Int) : Scenario() {
        // RFC 6455 §5.1: client → server frames must be masked.
        override val frameBytes: ByteArray =
            WsSeamContext.encodeFrame(WsFrame.text("a".repeat(payloadSize), maskKey = CLIENT_MASK_KEY))

        override fun runOnce() {
            feedAndDrain()
        }
    }

    private inner class BinaryFrame(payloadSize: Int) : Scenario() {
        override val frameBytes: ByteArray =
            WsSeamContext.encodeFrame(WsFrame.binary(ByteArray(payloadSize) { 0x61 }, maskKey = CLIENT_MASK_KEY))

        override fun runOnce() {
            feedAndDrain()
        }
    }

    private inner class PipelinedTextFrames(perBatch: Int, payloadSize: Int) : Scenario() {
        // A single buffer carrying `perBatch` text frames back-to-back —
        // simulates a chatty client that submits multiple messages in
        // one TCP segment, exercising the decoder's resume-from-partial
        // boundary handling.
        override val frameBytes: ByteArray = run {
            val one = WsSeamContext.encodeFrame(WsFrame.text("a".repeat(payloadSize), maskKey = CLIENT_MASK_KEY))
            ByteArray(one.size * perBatch).also { dst ->
                for (i in 0 until perBatch) {
                    one.copyInto(dst, i * one.size)
                }
            }
        }

        override fun runOnce() {
            feedAndDrain()
        }
    }

    @Test
    fun `per-frame allocation across hot-path scenarios`() {
        val scenarios = listOf(
            "A (text frame, ${SMALL_PAYLOAD}B)" to TextFrame(SMALL_PAYLOAD),
            "B (text frame, ${MEDIUM_PAYLOAD}B)" to TextFrame(MEDIUM_PAYLOAD),
            "C (binary frame, ${MEDIUM_PAYLOAD}B)" to BinaryFrame(MEDIUM_PAYLOAD),
            "D (binary frame, ${LARGE_PAYLOAD}B)" to BinaryFrame(LARGE_PAYLOAD),
            "E ($BATCH × text ${SMALL_PAYLOAD}B, pipelined)" to PipelinedTextFrames(BATCH, SMALL_PAYLOAD),
        )

        println("=== WsHotPath allocation (bytes / frame, iters=$ITERS × $TRIALS trials) ===")
        for ((name, scenario) in scenarios) {
            val trials = LongArray(TRIALS) { measure(ITERS, scenario) }
            trials.sort()
            val median = trials[TRIALS / 2]
            val perFrame = if (scenario is PipelinedTextFrames) median / BATCH else median
            val suffix = if (scenario is PipelinedTextFrames) "  (per-frame, batch=$BATCH)" else ""
            println("  $name median=$perFrame bytes  samples=${trials.toList()}$suffix")
        }

        for ((_, scenario) in scenarios) {
            scenario.ctx.close()
        }
    }

    companion object {
        private const val SMALL_PAYLOAD = 13
        private const val MEDIUM_PAYLOAD = 1_024
        private const val LARGE_PAYLOAD = 65_536
        private const val BATCH = 10
        private const val WARMUP = 2_000
        private const val ITERS = 10_000
        private const val TRIALS = 5

        /** Arbitrary 4-byte mask used for all client-side frames — RFC 6455 §5.1. */
        private const val CLIENT_MASK_KEY: Int = 0x37FA213D.toInt()
    }
}
