package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.Pipeline
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Backpressure tests for the pipeline-http routing handler.
 *
 * The pipeline-http routes are the *sync-handler* user-facing sample of the
 * keel backpressure idiom (the async server-http path uses suspending
 * `awaitFlushComplete` instead; see #784 / #785). These tests pin the two
 * resumable paths:
 *
 * - **SSE outbound** — emission parks when `isWritable` goes false and is
 *   resumed by `onWritabilityChanged`.
 * - **Echo inbound** — a `propagateWrite` that pushed past the high water
 *   mark triggers `pauseReads()`, and `onWritabilityChanged` re-arms it.
 *
 * The custom [BackpressureTransport] exposes `setWritable(...)` to drive
 * the watermark state machine deterministically without filling actual byte
 * counts; `pauseReads()` / `resumeReads()` counters then pin the wiring.
 */
class PipelineHttpRoutesBackpressureTest {

    private class BackpressureTransport : AbstractIoTransport(DefaultAllocator) {
        val written: MutableList<IoBuf> = mutableListOf()
        override var readEnabled: Boolean = false
        override val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined

        private var writableOverride: Boolean = true
        override val isWritable: Boolean
            get() = writableOverride

        var pauseReadsCount: Int = 0
            private set
        var resumeReadsCount: Int = 0
            private set

        override fun pauseReads() {
            pauseReadsCount++
            readEnabled = false
        }

        override fun resumeReads() {
            resumeReadsCount++
            readEnabled = true
        }

        /**
         * Drive `isWritable` to [writable] and fire the
         * `onWritabilityChanged` callback the pipeline registered on
         * the transport. Mirrors what `AbstractIoTransport.updatePendingBytes`
         * would do at the watermark boundary, but lets the test step the
         * state without paying for real pending-byte tracking.
         */
        fun setWritable(writable: Boolean) {
            if (writableOverride == writable) return
            writableOverride = writable
            onWritabilityChanged?.invoke(writable)
        }

        override fun write(buf: IoBuf) {
            buf.retain()
            written.add(buf)
        }

        override fun flush(): Boolean = true
        override fun shutdownOutput() {}

        /** No FIN to order: this double captures writes instead of sending them. */
        override fun sendFin() {}
        override fun close() {
            if (!markClosing()) return
            if (!markTeardownStarted()) return
            for (buf in written) buf.release()
            written.clear()
        }
    }

    private val transport = BackpressureTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("backpressure-test")) {}

    @AfterTest
    fun tearDown() {
        channel.close()
    }

    private fun pipeline(): Pipeline {
        val p = channel.pipeline
        installPipelineHttpHandlers(p)
        return p
    }

    private fun Pipeline.feed(text: String) {
        val bytes = text.encodeToByteArray()
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        notifyRead(buf)
    }

    private fun IoBuf.readString(): String {
        val bytes = ByteArray(readableBytes)
        readByteArray(bytes, 0, bytes.size)
        return bytes.decodeToString()
    }

    private fun collectWire(): String =
        transport.written.joinToString("") { it.readString() }

    @Test
    fun `sse stream parks emission when isWritable goes false and resumes on writability change`() {
        pipeline()
        // Drive the stream into a non-writable state BEFORE the request
        // arrives: the very first frame must not be flushed.
        transport.setWritable(false)

        channel.pipeline.feed(
            "GET /sse-stream?count=4&size=3 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n",
        )

        val wireBeforeResume = collectWire()
        // The 200 OK head was emitted (it goes through propagateWrite
        // straight away), but no SSE frame should have been; the pump
        // bailed out on the very first `isWritable` check.
        assertTrue(
            wireBeforeResume.startsWith("HTTP/1.1 200 OK\r\n"),
            "expected response head: $wireBeforeResume",
        )
        assertEquals(
            0,
            "data:".toRegex().findAll(wireBeforeResume).count(),
            "no SSE frames must be flushed while !isWritable: $wireBeforeResume",
        )

        transport.setWritable(true)

        val wire = collectWire()
        assertEquals(
            4,
            "data:".toRegex().findAll(wire).count(),
            "all 4 SSE frames must be flushed after writability resumed: $wire",
        )
        assertTrue(wire.endsWith("0\r\n\r\n"), "expected chunked terminator: $wire")
    }

    @Test
    fun `sse pump that mid-flight loses writability resumes from where it stopped`() {
        pipeline()
        channel.pipeline.feed(
            "GET /sse-stream?count=6&size=2 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n",
        )

        // All 6 frames already flushed under the default writable=true.
        val wireFull = collectWire()
        assertEquals(
            6,
            "data:".toRegex().findAll(wireFull).count(),
            "control: under writable=true, the pump emits all frames: $wireFull",
        )

        // Reset and replay with a mid-flight pause: flip non-writable
        // after the 3rd frame is written.
        transport.releaseAndClear()
        transport.setWritable(false)

        channel.pipeline.feed(
            "GET /sse-stream?count=6&size=2 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n",
        )
        // The pump parked on the very first check; no frames out.
        assertEquals(
            0,
            "data:".toRegex().findAll(collectWire()).count(),
            "no SSE frames must be flushed while !isWritable",
        )
        transport.setWritable(true)
        val resumed = collectWire()
        assertEquals(
            6,
            "data:".toRegex().findAll(resumed).count(),
            "all 6 SSE frames must flush after resume: $resumed",
        )
    }

    @Test
    fun `echo pauses reads when propagateWrite pushes past the watermark`() {
        pipeline()
        // Close the watermark BEFORE feeding so the response head + body
        // echo land while !isWritable; the post-write check in the echo
        // handler then trips pauseReads.
        transport.setWritable(false)
        channel.pipeline.feed(
            "POST /echo HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: 3\r\n" +
                "\r\n" +
                "foo",
        )

        assertEquals(
            1,
            transport.pauseReadsCount,
            "echo path must pauseReads after writing the chunk hit the high water mark",
        )
        assertEquals(
            0,
            transport.resumeReadsCount,
            "no resume yet — still !isWritable",
        )

        transport.setWritable(true)

        assertEquals(
            1,
            transport.resumeReadsCount,
            "onWritabilityChanged must resumeReads exactly once after the drain catches up",
        )
    }

    @Test
    fun `echo pauseReads fires at most once across a band of writes`() {
        pipeline()
        transport.setWritable(false)
        // Two-chunk body fed across two reads (the first carries the
        // header + chunk 1, the second carries chunk 2). The decoder
        // emits one HttpBody per chunk; the second propagateWrite must
        // NOT double-pause (the flag guards re-entry).
        channel.pipeline.feed(
            "POST /echo HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: 6\r\n" +
                "\r\n" +
                "foo",
        )
        channel.pipeline.feed("bar")
        assertEquals(1, transport.pauseReadsCount, "second write must NOT double-pause")

        transport.setWritable(true)
        assertEquals(1, transport.resumeReadsCount, "single resume on writability resumed")
    }

    @Test
    fun `onInactive resets parked SSE state silently`() {
        pipeline()
        transport.setWritable(false)
        channel.pipeline.feed(
            "GET /sse-stream?count=4&size=3 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n",
        )
        // The pump parked on the very first writability check; no SSE
        // frames were flushed.
        assertEquals(
            0,
            "data:".toRegex().findAll(collectWire()).count(),
            "no SSE frames before resume",
        )

        // Simulate a peer-side teardown — the engine drives notifyInactive
        // before the writability path catches up.
        channel.pipeline.notifyInactive()

        // After onInactive, even a delayed writability flip must not
        // resume the pump (the state was reset silently) and must not
        // call resumeReads on a closing transport.
        transport.setWritable(true)
        assertEquals(
            0,
            "data:".toRegex().findAll(collectWire()).count(),
            "onInactive cleared pendingSseEmission — pump must NOT resume after teardown",
        )
        assertEquals(
            0,
            transport.resumeReadsCount,
            "onInactive must reset state silently — never call resumeReads on a closing transport",
        )
    }

    /**
     * Release the captured outbound buffers and clear the list without
     * driving the lifecycle state machine. Used by tests that want to
     * inspect two phases of wire output independently.
     */
    private fun BackpressureTransport.releaseAndClear() {
        for (buf in written) buf.release()
        written.clear()
    }
}
