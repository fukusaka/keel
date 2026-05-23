package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Red-Green regression test for the `headers.release()` leak in
 * [installPipelineHttpHandlers]' routing terminal.
 *
 * Background: PR #596 (Variant Y byte-range header storage) gave
 * `HttpHeaders` a buffer-lifetime contract — the parsed `HttpHeaders`
 * retains the recv `IoBuf` until `release()` is called, because name /
 * value slots point into the recv buffer's byte range.
 *
 * The benchmark routing terminal previously consumed `HttpRequestHead`
 * without calling `msg.headers.release()`, so every request leaked its
 * recv buffer. On engines with a small fixed pool — io_uring's provided
 * buffer ring has only `DEFAULT_BUFFER_COUNT = 64` slots — the ring
 * exhausted within ~64 requests and throughput collapsed by ~6000×
 * (PR #600).
 *
 * **Verification** (this test): drive N=128 `/hello` requests through
 * the full encoder → decoder → routing pipeline via a
 * [TrackingAllocator]. After draining the channel, every allocate must
 * have a matching release.
 *
 * - **Red** (before PR #600 — `BenchmarkRoutingHandler` omits the
 *   `headers.release()` call): `outstandingCount > 0`, one orphaned
 *   recv buffer per request.
 * - **Green** (after PR #600 — both regular-path and WS-upgrade
 *   early-return paths call `msg.headers.release()`):
 *   `outstandingCount == 0`.
 */
class BenchmarkRoutingHandlerLeakTest {

    private class CapturingTransport : AbstractIoTransport(DefaultAllocator) {
        val written: MutableList<IoBuf> = mutableListOf()
        override var readEnabled: Boolean = false
        override val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined
        override fun write(buf: IoBuf) { buf.retain(); written.add(buf) }
        override fun flush(): Boolean = true
        override fun shutdownOutput() {}
        override fun close() {
            if (!markClosing()) return
            if (!markTeardownStarted()) return
            for (buf in written) buf.release()
            written.clear()
        }
    }

    // Dedicated tracker for feed (recv) buffers only — decouples the
    // assertion from any response-side buffer accounting in the test
    // harness so the test isolates exactly the contract this PR fixes
    // (`HttpRequestHead.headers.release()` returning the recv buffer
    // to the pool).
    private val feedTracker = TrackingAllocator()

    private val transport = CapturingTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("leak-test")) {}

    @AfterTest
    fun tearDown() {
        channel.close()
    }

    @Test
    fun `hello requests do not leak recv buffers`() {
        installPipelineHttpHandlers(channel.pipeline)

        // 128 > io_uring DEFAULT_BUFFER_COUNT (64) so a per-request leak
        // would manifest as twice the ring capacity outstanding. The
        // count also exceeds 64 deliberately to mirror the production
        // exhaustion threshold that motivated PR #600.
        val request = "GET /hello HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n"
        val requestBytes = request.encodeToByteArray()
        val requestCount = 128
        repeat(requestCount) {
            val buf = feedTracker.allocate(requestBytes.size)
            buf.writeByteArray(requestBytes, 0, requestBytes.size)
            channel.pipeline.notifyRead(buf)
        }

        // Every recv buffer must be back in the pool: the decoder's
        // `onReadTyped` release + the routing terminal's
        // `msg.headers.release()` together drop both refs taken on the
        // request-decode path. A per-request leak shows up as
        // `outstandingCount == requestCount`.
        feedTracker.assertNoLeaks(
            "recv buffer leak in BenchmarkRoutingHandler (PR #600 regression): " +
                "allocate=${feedTracker.allocateCount} release=${feedTracker.releaseCount}",
        )
    }
}
