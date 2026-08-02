package io.github.fukusaka.keel.codec.http

import com.sun.management.ThreadMXBean
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import java.lang.management.ManagementFactory
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Heap-allocation A/B harness for [HttpBodyAggregator]'s body accumulation.
 *
 * Aggregates a [BODY_SIZE]-byte body delivered in [CHUNK_COUNT] chunks
 * [ITERATIONS] times and reports the heap bytes allocated per aggregation
 * (`com.sun.management.ThreadMXBean.getThreadAllocatedBytes`). The held
 * pooled [io.github.fukusaka.keel.buf.IoBuf] chunks are off-heap, so the
 * measured heap delta between the two implementations isolates the
 * intermediate body array(s): the old doubling `ByteArray` reallocated
 * `O(log)` growing intermediates plus a final trim copy, while the pooled
 * `IoBufMutableChunks` holds the chunks off-heap and flattens once.
 *
 * Git-based A/B (this harness compiled against each [HttpBodyAggregator]),
 * local JVM, 256 KiB body in 64 x 4 KiB chunks, 10k iterations (2026-06-30):
 *   old (doubling ByteArray): 796,433 heap-bytes/aggregation
 *   new (pooled hold):        277,098 heap-bytes/aggregation  (-65%)
 *
 * The old path allocates ~3x: the abandoned doubling intermediates plus the
 * final trim copy. The new path allocates only the single end-of-body flatten
 * (the held chunks are off-heap pooled and excluded from this heap count).
 */
class HttpBodyAggregatorAllocBenchmark {

    private val transport = TestIoTransport(allocator = DefaultAllocator)
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("bench")) {}

    private object Sink : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            // Drop the aggregated HttpRequest; we only measure the build cost.
        }
    }

    // Re-run: remove @Ignore, then
    //   ./gradlew :keel-codec-http:jvmTest --tests '*HttpBodyAggregatorAllocBenchmark*' --rerun-tasks
    // and read the HBA-ALLOC line from the test stdout / JUnit XML. For the A/B,
    // compile this against `git show main:.../HttpBodyAggregator.kt` for the old number.
    @Ignore
    @Test
    fun `measure aggregated body heap allocation per request`() {
        val pipeline = channel.pipeline
        pipeline.addLast("aggregator", HttpBodyAggregator(maxContentLength = MAX_LEN))
        pipeline.addLast("sink", Sink)

        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val tid = Thread.currentThread().threadId()
        val chunkBytes = ByteArray(CHUNK_SIZE) { 'x'.code.toByte() }

        fun aggregateOnce() {
            pipeline.notifyRead(HttpRequestHead(HttpMethod.POST, "/bench"))
            repeat(CHUNK_COUNT) {
                val buf = DefaultAllocator.allocate(CHUNK_SIZE)
                buf.writeByteArray(chunkBytes, 0, CHUNK_SIZE)
                pipeline.notifyRead(HttpBody(buf))
            }
            pipeline.notifyRead(HttpBodyEnd.EMPTY)
        }

        repeat(WARMUP) { aggregateOnce() }
        val before = bean.getThreadAllocatedBytes(tid)
        repeat(ITERATIONS) { aggregateOnce() }
        val after = bean.getThreadAllocatedBytes(tid)
        val perAggregation = (after - before).toDouble() / ITERATIONS

        println(
            "HBA-ALLOC perAgg=%.0f heap-bytes (body=%dB in %d x %dB chunks, iters=%d)".format(
                perAggregation,
                BODY_SIZE,
                CHUNK_COUNT,
                CHUNK_SIZE,
                ITERATIONS,
            ),
        )
    }

    private companion object {
        const val CHUNK_SIZE = 4096
        const val CHUNK_COUNT = 64
        const val BODY_SIZE = CHUNK_SIZE * CHUNK_COUNT
        const val MAX_LEN = 1 shl 24
        const val WARMUP = 1000
        const val ITERATIONS = 10000
    }
}
