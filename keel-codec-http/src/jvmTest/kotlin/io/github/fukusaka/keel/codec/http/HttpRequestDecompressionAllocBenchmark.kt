package io.github.fukusaka.keel.codec.http

import com.sun.management.ThreadMXBean
import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.CompressionRegistry
import io.github.fukusaka.keel.compression.Decoder
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecoderSession
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import java.lang.management.ManagementFactory
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Heap-allocation A/B harness for [HttpRequestDecompressionHandler]'s
 * aggregated-decode path.
 *
 * Aggregate-decodes a [INPUT_SIZE]-byte compressed body that expands [FACTOR]x
 * ([ITERATIONS] times) and reports heap bytes allocated per decode
 * (`com.sun.management.ThreadMXBean.getThreadAllocatedBytes`). The old path
 * drained the decoder's scratch [IoBuf] into a doubling `ByteArray` sink (the
 * abandoned doubling intermediates plus a trim copy are heap garbage); the new
 * path decodes straight into a pooled [io.github.fukusaka.keel.buf.IoBufAccumulator]
 * (off-heap chunks) and flattens once. The held chunks are off-heap, so the
 * measured heap delta isolates the doubling intermediates the new path drops.
 *
 * [FillDecoder] approximates a real streaming inflate (bounded O(n) output, no
 * internal re-accumulation) so the delta reflects the handler's sink, not the
 * test decoder.
 *
 * Git-based A/B (this harness compiled against each handler), local JVM,
 * 4 KiB compressed body -> 256 KiB decoded (64x), 10k iterations (2026-07-01):
 *   old (doubling ByteArray sink): 517,239 bytes/decode
 *   new (pooled IoBufAccumulator): 278,458 bytes/decode  (-46%)
 *
 * The old path allocates the abandoned doubling intermediates (~240 KiB:
 * 16 -> 32 -> 64 -> 128 KiB) plus the held result; the new path allocates only
 * the single end-of-body flatten (the held chunks are off-heap pooled and
 * excluded from this heap count).
 */
class HttpRequestDecompressionAllocBenchmark {

    private val transport = TestIoTransport(allocator = DefaultAllocator)
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("bench")) {}

    private object Sink : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            // Drop the decoded HttpRequest; we only measure the decode build cost.
        }
    }

    /**
     * Test decoder approximating a streaming inflate: expands the input [factor]x
     * and writes the output in bounded passes straight into the handler's output
     * chunk from a pre-filled unit buffer — no internal re-accumulation, so the
     * A/B delta reflects the handler's sink rather than the decoder.
     */
    private class FillDecoder(private val factor: Int) : Decoder {
        override val name: String = "x$factor"
        override fun newSession(allocator: BufferAllocator, options: DecoderOptions): DecoderSession =
            object : DecoderSession {
                private var remaining = 0L
                private val unit = ByteArray(UNIT) { 'A'.code.toByte() }
                private val drainSink = ByteArray(DRAIN)

                override fun update(input: IoBuf, output: IoBuf): CodecStatus {
                    var n = input.readableBytes
                    remaining += n.toLong() * factor
                    while (n > 0) {
                        val take = minOf(n, drainSink.size)
                        input.readByteArray(drainSink, 0, take)
                        n -= take
                    }
                    return drain(output)
                }

                override fun finish(output: IoBuf): CodecStatus =
                    if (remaining == 0L) CodecStatus.FINISHED else drain(output)

                override fun reset() {
                    remaining = 0L
                }

                override fun close() {
                    remaining = 0L
                }

                private fun drain(output: IoBuf): CodecStatus {
                    if (remaining == 0L) return CodecStatus.NEED_INPUT
                    val cap = output.writableBytes
                    if (cap == 0) return CodecStatus.NEED_OUTPUT
                    val take = minOf(minOf(cap.toLong(), remaining), unit.size.toLong()).toInt()
                    output.writeByteArray(unit, 0, take)
                    remaining -= take
                    return if (remaining == 0L) CodecStatus.NEED_INPUT else CodecStatus.NEED_OUTPUT
                }
            }

        companion object {
            const val UNIT = 8192
            const val DRAIN = 256
        }
    }

    // Re-run: remove @Ignore, then
    //   ./gradlew :keel-codec-http:jvmTest --tests '*HttpRequestDecompressionAllocBenchmark*' --rerun-tasks
    // and read the HRD-ALLOC line from the test stdout / JUnit XML. For the A/B,
    // compile this against `git show main:.../HttpRequestDecompressionHandler.kt`
    // for the old number.
    @Ignore
    @Test
    fun `measure aggregated decode heap allocation per request`() {
        val registry = CompressionRegistry().apply { registerDecoder(FillDecoder(FACTOR)) }
        val pipeline = channel.pipeline
        pipeline.addLast(
            "decompress",
            HttpRequestDecompressionHandler(
                registry,
                DefaultAllocator,
                decompressionLimit = Long.MAX_VALUE,
                ratioLimit = Int.MAX_VALUE,
            ),
        )
        pipeline.addLast("sink", Sink)

        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val tid = Thread.currentThread().threadId()
        val compressed = ByteArray(INPUT_SIZE) { 'A'.code.toByte() }

        fun decodeOnce() {
            pipeline.notifyRead(
                HttpRequest(
                    method = HttpMethod.POST,
                    uri = "/upload",
                    headers = HttpHeaders().apply {
                        add("Content-Encoding", "x$FACTOR")
                        add("Content-Length", INPUT_SIZE.toString())
                    },
                    body = compressed,
                ),
            )
        }

        repeat(WARMUP) { decodeOnce() }
        val before = bean.getThreadAllocatedBytes(tid)
        repeat(ITERATIONS) { decodeOnce() }
        val after = bean.getThreadAllocatedBytes(tid)
        val perDecode = (after - before).toDouble() / ITERATIONS

        println(
            "HRD-ALLOC perDecode=%.0f heap-bytes (in=%dB x%d -> %dB decoded, iters=%d)".format(
                perDecode,
                INPUT_SIZE,
                FACTOR,
                INPUT_SIZE * FACTOR,
                ITERATIONS,
            ),
        )
    }

    private companion object {
        const val INPUT_SIZE = 4096
        const val FACTOR = 64
        const val WARMUP = 1000
        const val ITERATIONS = 10000
    }
}
