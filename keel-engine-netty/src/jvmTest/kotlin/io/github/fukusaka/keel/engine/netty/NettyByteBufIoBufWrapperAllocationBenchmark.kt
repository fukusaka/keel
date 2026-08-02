package io.github.fukusaka.keel.engine.netty

import com.sun.management.ThreadMXBean
import io.github.fukusaka.keel.buf.NoOpLifecycleListener
import io.netty.buffer.ByteBuf
import io.netty.buffer.PooledByteBufAllocator
import io.netty.util.concurrent.FastThreadLocalThread
import java.lang.management.ManagementFactory
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Measures per-receive JVM allocation for the real production code path —
 * [NettyByteBufIoBuf.wrapInbound] (unpooled wrapper, pre-pooling) against
 * [NettyByteBufIoBuf.borrowInbound] (the [Recycler][io.netty.util.Recycler]-backed
 * pooled wrapper `NettyIoTransport.channelRead` now uses) — the decision-aid
 * measurement a per-EventLoop Netty wrapper-object pool backlog item
 * called for before implementing it.
 *
 * Must run on a [FastThreadLocalThread]: [NettyByteBufIoBuf]'s `RECYCLER`
 * only pools on that thread type (see its KDoc) — on the JUnit runner
 * thread `borrowInbound` would always hand back a fresh, unpooled instance,
 * silently degrading to variant A's shape and hiding the effect being
 * measured.
 *
 * Both variants register the resulting buffer's `capacity` into a size-1
 * accumulator field, read after each `measure()` call, so escape analysis
 * can't eliminate the allocation the way an earlier version of
 * `NettyFlushListenerAllocationBenchmark` was caught doing (see that
 * file's KDoc for the pitfall).
 *
 * Not a unit test — runs as a `@Test` so it executes under the normal
 * `jvmTest` task; inspect stdout for the numbers. Does not assert.
 *
 * **Result (2026-07-12, JVM, real code, `FastThreadLocalThread`)**: A
 * (`wrapInbound`, unpooled) median 188 B/receive, B (`borrowInbound`,
 * pooled) median 139 B/receive — 49 B/receive saved (26% reduction).
 * Somewhat larger than the standalone synthetic reconstruction this
 * benchmark previously used (39 B/receive, 13%), but the same order of
 * magnitude. Still modest compared to the flush-listener pool (#930, 43%)
 * and the `ArrayList` snapshot pools (#928/#929, 64%): most of both
 * variants' allocation is the pooled `ByteBuf`'s own bookkeeping plus the
 * `nioBuffer()` view, neither of which wrapper pooling touches.
 */
// @Ignore: one-time measurement (no functional assertion) — a decision aid
// for the per-EventLoop Netty wrapper-object pool, run before committing
// to implementing it (design-principles.md "パフォーマンス改善の検証").
// Re-run: remove @Ignore, then
//   ./gradlew :keel-engine-netty:jvmTest --tests "*NettyByteBufIoBufWrapperAllocationBenchmark"
@Ignore
class NettyByteBufIoBufWrapperAllocationBenchmark {

    private val tmx = ManagementFactory.getThreadMXBean() as ThreadMXBean
    private val nettyAlloc = PooledByteBufAllocator.DEFAULT

    private fun measure(iterations: Int, cycle: () -> Any): Long {
        var sink: Any? = null
        repeat(WARMUP) { sink = cycle() }
        val start = tmx.getThreadAllocatedBytes(Thread.currentThread().threadId())
        repeat(iterations) { sink = cycle() }
        val end = tmx.getThreadAllocatedBytes(Thread.currentThread().threadId())
        check(sink != null)
        return (end - start) / iterations
    }

    private fun inboundByteBuf(): ByteBuf {
        val byteBuf = nettyAlloc.directBuffer(PAYLOAD, PAYLOAD)
        byteBuf.writeBytes(PAYLOAD_BYTES)
        return byteBuf
    }

    @Test
    fun `per-receive allocation wrapInbound vs borrowInbound on a FastThreadLocalThread`() {
        var summary = ""
        val thread = FastThreadLocalThread {
            val trialsA = LongArray(TRIALS) {
                measure(ITERS) {
                    val byteBuf = inboundByteBuf()
                    val buf = NettyByteBufIoBuf.wrapInbound(byteBuf, NoOpLifecycleListener)
                    val cap = buf.capacity // touch — prevent dead-code elimination
                    buf.release()
                    cap
                }
            }

            val trialsB = LongArray(TRIALS) {
                measure(ITERS) {
                    val byteBuf = inboundByteBuf()
                    val buf = NettyByteBufIoBuf.borrowInbound(byteBuf, NoOpLifecycleListener)
                    val cap = buf.capacity // touch
                    buf.release()
                    cap
                }
            }

            trialsA.sort()
            trialsB.sort()
            val medA = trialsA[TRIALS / 2]
            val medB = trialsB[TRIALS / 2]

            summary = buildString {
                appendLine(
                    "=== NettyByteBufIoBuf wrapper allocation (bytes / receive, " +
                        "payload=${PAYLOAD}B, iters=$ITERS × $TRIALS trials) ===",
                )
                appendLine("  A (wrapInbound, unpooled) median=$medA bytes  samples=${trialsA.toList()}")
                appendLine("  B (borrowInbound, pooled) median=$medB bytes  samples=${trialsB.toList()}")
                append("  Δ (A-B)                   ${medA - medB} bytes / receive")
            }
        }
        thread.start()
        thread.join(THREAD_JOIN_TIMEOUT_MS)
        check(!thread.isAlive) { "benchmark did not finish within ${THREAD_JOIN_TIMEOUT_MS}ms" }
        println(summary)
    }

    companion object {
        private const val PAYLOAD = 13 // /hello size
        private val PAYLOAD_BYTES = ByteArray(PAYLOAD) { it.toByte() }
        private const val WARMUP = 2000
        private const val ITERS = 10_000
        private const val TRIALS = 5
        private const val THREAD_JOIN_TIMEOUT_MS = 30_000L
    }
}
