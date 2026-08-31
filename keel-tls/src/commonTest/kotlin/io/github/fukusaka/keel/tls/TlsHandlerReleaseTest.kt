package io.github.fukusaka.keel.tls

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.DuplexHandler
import io.github.fukusaka.keel.pipeline.EventLoopTimer
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.pipeline.TimerHandle
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a TLS handler gives back when the connection it protects ends, and
 * what it must stop doing afterwards.
 *
 * The session is not garbage. On the native backends it is an `SSL*`, its
 * `BIO`, and a manually allocated context, and nothing reclaims those but
 * [TlsCodec.close] — of which this handler is the only caller in the library's
 * own sources. It called it from one place: the handler being taken out of the pipeline, which
 * only a protocol switch does. An ordinary connection ending reached it from
 * nowhere, so every TLS connection left a session behind.
 *
 * The inactivation is the signal that already arrives on those endings, so
 * that is where the release goes. Which makes the second half necessary: once
 * the session is gone this handler must not use it. The inactivation travels
 * inbound and this handler sits near the head, so handlers *after* it are
 * still running when it returns — and anything they write travels back
 * outbound through here.
 *
 * Not covered here, and stated because the KDoc on the release says so too: a
 * connection closed locally still reaches none of this. `close()` goes to the
 * transport without passing the handlers at all, which is a separate change.
 */
class TlsHandlerReleaseTest {

    /**
     * Counts what it was asked to give back, and counts every use after.
     *
     * [oneByteAtATime] makes the handler's loops turn more than once — a
     * record per byte — so a release landing *inside* a loop has a next turn
     * to be caught on. Without it every loop finishes in one pass and the
     * guards inside them are never reached.
     */
    private class CountingCodec(
        private val oneByteAtATime: Boolean = false,
        handshakeDoneInitially: Boolean = true,
        /**
         * Asks for more input instead of a wrap while the handshake is
         * unfinished. Leaves a remainder saved *and* the handshake deadline
         * still armed — the one state where a release step failing has a
         * later step left to cost.
         */
        private val stallsForInput: Boolean = false,
        /** Answers `CLOSED` from `unprotect`, as a codec does on a peer's close_notify. */
        private val peerSaidClosed: Boolean = false,
        /** Answers `CLOSED` from `protect` — the same news noticed while sending. */
        private val closedWhileSending: Boolean = false,
    ) : TlsCodec {
        var closeCount: Int = 0
            private set
        var usedAfterClose: Int = 0
            private set

        /**
         * Every turn, not only the ones after the release.
         *
         * [usedAfterClose] describes a consequence — a use that should not
         * have happened — and reading it alone leaves the loop's own
         * behaviour to be inferred from a number the flush loop's iteration
         * cap happens to bound. This says what the loop did.
         */
        var protectCount: Int = 0
            private set

        /**
         * Counted like the other two, because it reads the session just as
         * they do — `SSL_is_init_finished` on a native backend. Leaving it
         * uncounted is what let a read of a freed session past these cases.
         */
        override val isHandshakeComplete: Boolean
            get() {
                if (closeCount > 0) usedAfterClose++
                return handshakeDone
            }

        private var handshakeDone: Boolean = handshakeDoneInitially
        override val negotiatedProtocol: String? = null
        override val peerCertificates: List<ByteArray> = emptyList()

        override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult {
            if (closeCount > 0) usedAfterClose++
            if (peerSaidClosed) return TlsCodecResult(TlsResult.CLOSED, 0, 0)
            if (!handshakeDone) {
                return if (stallsForInput) {
                    TlsCodecResult(TlsResult.NEED_MORE_INPUT, 0, 0)
                } else {
                    TlsCodecResult(TlsResult.NEED_WRAP, 0, 0)
                }
            }
            if (!oneByteAtATime) {
                // Consumes nothing and asks for more, which is what leaves a
                // partial record behind in the handler.
                return TlsCodecResult(TlsResult.NEED_MORE_INPUT, 0, 0)
            }
            plaintext.writeByte(0x41)
            return TlsCodecResult(TlsResult.OK, 1, 1)
        }

        override fun protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult {
            protectCount++
            if (closeCount > 0) usedAfterClose++
            if (closedWhileSending) return TlsCodecResult(TlsResult.CLOSED, 0, 0)
            if (!handshakeDone) {
                // One more handshake record every time it is asked, so the
                // flush loop keeps turning until something stops it.
                ciphertext.writeByte(0x16)
                return TlsCodecResult(TlsResult.NEED_WRAP, 0, 1)
            }
            if (!oneByteAtATime) {
                val readable = plaintext.readableBytes
                return TlsCodecResult(TlsResult.OK, readable, 0)
            }
            ciphertext.writeByte(0x42)
            return TlsCodecResult(TlsResult.OK, 1, 1)
        }

        override fun close() {
            closeCount++
            if (failOnClose) throw IllegalStateException(SESSION_REFUSED)
        }

        var failOnClose: Boolean = false
    }

    /** Hands out deadlines that refuse to be cancelled. */
    private class RefusingTimer : EventLoopTimer {
        override fun schedule(delayMillis: Long, task: () -> Unit): TimerHandle =
            object : TimerHandle {
                override fun touch() = Unit
                override fun cancel(): Unit = throw IllegalStateException(DEADLINE_REFUSED)
            }
    }

    /** Ends the connection from inside a write or a flush, as a failing one does. */
    private class EndingTransport(
        allocator: TrackingAllocator,
        private val endOn: End,
    ) : TestIoTransport(allocator) {
        enum class End { WRITE, FLUSH, NOTHING }

        var endTheConnection: (() -> Unit)? = null
        private var ended = false

        private fun endOnce() {
            if (ended) return
            ended = true
            endTheConnection?.invoke()
        }

        override fun write(buf: IoBuf) {
            super.write(buf)
            if (endOn == End.WRITE) endOnce()
        }

        override fun flush(): Boolean {
            val flushed = super.flush()
            if (endOn == End.FLUSH) endOnce()
            return flushed
        }

        var timer: EventLoopTimer? = null
        override val eventLoopTimer: EventLoopTimer? get() = timer
    }

    /** Keeps what was logged, so a report can be asserted rather than assumed. */
    private class RecordingLogger : Logger {
        val records: MutableList<Pair<LogLevel, String>> = mutableListOf()
        val warnings: List<String> get() = records.filter { it.first == LogLevel.WARN }.map { it.second }

        override fun isLoggable(level: LogLevel) = true

        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            records.add(level to message.toString())
        }
    }

    private class Fixture(
        oneByteAtATime: Boolean = false,
        handshakeDone: Boolean = true,
        endOn: EndingTransport.End = EndingTransport.End.NOTHING,
        handshakeTimeoutMillis: Long = 0,
        refusingTimer: Boolean = false,
        stallsForInput: Boolean = false,
        peerSaidClosed: Boolean = false,
        closedWhileSending: Boolean = false,
    ) {
        val tracker = TrackingAllocator()
        val transport = EndingTransport(tracker, endOn)
        val codec = CountingCodec(oneByteAtATime, handshakeDone, stallsForInput, peerSaidClosed, closedWhileSending)
        val handler = TlsHandler(codec, handshakeTimeoutMillis = handshakeTimeoutMillis)
        val heard: MutableList<String> = mutableListOf()
        val logger = RecordingLogger()
        val channel = object : AbstractPipelinedChannel(transport, logger) {}

        init {
            if (refusingTimer) transport.timer = RefusingTimer()
            channel.pipeline.addLast(TLS, handler)
            channel.pipeline.addLast(
                "listens",
                object : DuplexHandler {
                    override fun onInactive(ctx: PipelineHandlerContext) {
                        heard.add("inactive")
                        ctx.propagateInactive()
                    }
                },
            )
            transport.endTheConnection = { channel.pipeline.notifyInactive() }
        }

        /** Ends the connection the first time this handler is read to. */
        fun endTheConnectionOnFirstRead() {
            channel.pipeline.addLast(
                "ends-it",
                object : DuplexHandler {
                    private var done = false

                    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                        (msg as? IoBuf)?.release()
                        if (done) return
                        done = true
                        channel.pipeline.notifyInactive()
                    }
                },
            )
        }

        /**
         * Leaves a half-read record in the handler: the codec consumes none of
         * this and asks for more, so the handler keeps the remainder for a
         * read that, on these paths, never comes.
         */
        fun feedPartialRecord() {
            val buf = tracker.allocate(8)
            repeat(4) { buf.writeByte(0x16) }
            channel.pipeline.notifyRead(buf)
        }

        fun writeSomething() {
            val buf = tracker.allocate(8)
            repeat(4) { buf.writeByte(0x41) }
            channel.pipeline.requestWrite(buf)
        }
    }

    @Test
    fun `a connection that ends gives the TLS session back`() {
        val f = Fixture()

        f.channel.pipeline.notifyInactive()

        assertEquals(
            1,
            f.codec.closeCount,
            "the session is released when the connection ends — on a native backend this is an SSL*, " +
                "its BIO and a manual allocation that nothing else reclaims",
        )
    }

    @Test
    fun `a connection that ends gives back the buffer holding a half-read record`() {
        val f = Fixture()
        f.feedPartialRecord()
        assertTrue(
            f.tracker.outstandingCount > 0,
            "the handler is holding the remainder, which is what makes this worth asking about",
        )

        f.channel.pipeline.notifyInactive()

        assertEquals(
            0,
            f.tracker.outstandingCount,
            "and it is pooled, so it goes back with the session rather than only on a protocol switch",
        )
    }

    @Test
    fun `a write arriving after the connection ended does not reach the released session`() {
        val f = Fixture()
        f.channel.pipeline.notifyInactive()

        // The reachable shape, not a hypothetical: the inactivation travels
        // inbound and this handler sits near the head, so every handler after
        // it runs while this one has already released. The server's own
        // cancellation of its connection scope is cooperative, so a request
        // past its last suspension point still writes its response.
        f.writeSomething()

        assertEquals(
            0,
            f.codec.usedAfterClose,
            "a released session is not asked to protect anything — on a native backend that is a " +
                "freed SSL* and a freed BIO context",
        )
    }

    @Test
    fun `a read arriving after the connection ended does not reach the released session`() {
        val f = Fixture()
        f.channel.pipeline.notifyInactive()

        f.feedPartialRecord()

        assertEquals(0, f.codec.usedAfterClose, "nor to unprotect anything")
        assertEquals(0, f.tracker.outstandingCount, "and the bytes it will not read are given back")
    }

    @Test
    fun `a connection ended while decrypting stops before the next record`() {
        // The reachable re-entrancy: this loop hands each decrypted record to
        // the handlers below, and one of them ends the connection. Without a
        // check on every turn, the next turn decrypts with a session that was
        // freed while the stack was down there.
        val f = Fixture(oneByteAtATime = true)
        f.endTheConnectionOnFirstRead()

        val buf = f.tracker.allocate(8)
        repeat(4) { buf.writeByte(0x16) }
        f.channel.pipeline.notifyRead(buf)

        // The release first, and the guard second. Each of these cases runs
        // a chain -- something ends the connection, the inactivation walks the
        // pipeline, this handler releases -- and only then is there anything
        // for the guard to do. Asserted in that order so a failure names the
        // link that broke rather than the last one.
        assertEquals(1, f.codec.closeCount, "the connection ended and the session was released")
        assertEquals(
            0,
            f.codec.usedAfterClose,
            "and the loop stopped there rather than decrypting the rest with a freed session",
        )
    }

    @Test
    fun `a connection ended while sending a record stops before the next one`() {
        // Same shape outbound: each record goes to the transport, and a
        // transport that fails ends the connection from inside that call.
        val f = Fixture(oneByteAtATime = true, endOn = EndingTransport.End.WRITE)

        val buf = f.tracker.allocate(8)
        repeat(4) { buf.writeByte(0x41) }
        f.channel.pipeline.requestWrite(buf)

        assertEquals(1, f.codec.closeCount, "the connection ended and the session was released")
        assertEquals(0, f.codec.usedAfterClose, "and the loop stopped rather than encrypting with a freed session")
    }

    @Test
    fun `a connection ended while flushing a handshake response stops that loop`() {
        // The handshake flush is the one loop that flushes, so a transport
        // whose flush ends the connection re-enters it. It would otherwise
        // keep asking a freed session for the next handshake record.
        val f = Fixture(handshakeDone = false, endOn = EndingTransport.End.FLUSH)

        val buf = f.tracker.allocate(8)
        repeat(4) { buf.writeByte(0x16) }
        f.channel.pipeline.notifyRead(buf)

        assertEquals(1, f.codec.closeCount, "the connection ended and the session was released")
        assertEquals(1, f.codec.protectCount, "the flush loop turned once and then stopped")
        assertEquals(0, f.codec.usedAfterClose, "so nothing reached the released session")
    }

    @Test
    fun `a release step that refuses does not cost the others`() {
        // The latch is spent the moment the release starts, so a step that
        // throws would take every later step with it for good — and there is
        // no second chance at any of them: the session is invisible to the
        // collector and the record buffer is pooled. Here the deadline refuses
        // to be cancelled, and both of its neighbours are still given back.
        val f = Fixture(
            handshakeDone = false,
            handshakeTimeoutMillis = 1_000,
            refusingTimer = true,
            stallsForInput = true,
        )
        // Both neighbours have to be real for this to measure anything: the
        // deadline armed (the handshake is unfinished) and a remainder saved
        // (the codec asked for more input). Without the remainder there is
        // nothing for the refusing step to cost, and removing its guard goes
        // unnoticed — measured.
        f.feedPartialRecord()
        assertTrue(f.tracker.outstandingCount > 0, "a remainder is being held")

        runCatching { f.channel.pipeline.notifyInactive() }

        // The handshake records this fixture emits are held by the transport,
        // not by the handler, so they are handed back first — otherwise this
        // counts buffers the release was never responsible for.
        f.transport.releaseWritten()

        assertEquals(1, f.codec.closeCount, "the session was given back")
        assertEquals(
            0,
            f.tracker.outstandingCount,
            "and so was the record — the refusing deadline sits between them and took neither",
        )
    }

    @Test
    fun `a release that refuses still lets the connection's end travel on`() {
        // The handlers below this one learn the connection ended from this
        // signal and nothing else. A release that throws must not swallow it:
        // the invoker would turn the throw into an error event, which the
        // suspend bridge does not listen for, leaving its queued buffers held
        // and a parked reader unwoken.
        val f = Fixture()
        f.codec.failOnClose = true

        runCatching { f.channel.pipeline.notifyInactive() }

        assertEquals(listOf("inactive"), f.heard, "the handler below still heard the connection end")
    }

    @Test
    fun `a close_notify the codec reports releases the session as well`() {
        // The ending this handler notices itself rather than being told about.
        // The inactivation it reports travels *away* from it, so it never
        // arrives back at its own `onInactive` — a release hung only on that
        // callback would miss this one entirely. A peer may close_notify
        // without a TCP FIN, and then nothing else would have released it.
        val f = Fixture(peerSaidClosed = true)

        f.feedPartialRecord()

        assertEquals(1, f.codec.closeCount, "the session went back on the ending the codec reported")
        assertEquals(listOf("inactive"), f.heard, "and the handlers below were told, as they were before")
    }

    @Test
    fun `a close_notify noticed while sending releases the session as well`() {
        // The same ending, noticed on the other side of the handler: the codec
        // answers CLOSED while encrypting rather than while decrypting. It is
        // the same news and owes the same release.
        val f = Fixture(closedWhileSending = true)

        f.writeSomething()

        assertEquals(1, f.codec.closeCount, "the session went back on the ending noticed while sending")
        assertEquals(listOf("inactive"), f.heard, "and the handlers below were told")
    }

    @Test
    fun `a close_notify noticed while flushing a handshake releases the session as well`() {
        // And on the third path that reports it: the handshake flush, which
        // asks the codec for the next record and is told there will not be one.
        val f = Fixture(handshakeDone = false, closedWhileSending = true)

        f.feedPartialRecord()

        assertEquals(1, f.codec.closeCount, "the session went back on the ending noticed while handshaking")
        assertEquals(listOf("inactive"), f.heard, "and the handlers below were told")
    }

    @Test
    fun `an outbound message dropped after the release is named`() {
        // Dropping it is right — there is nothing to encrypt with, and sending
        // the plaintext on would be worse. Saying nothing is not: the caller
        // has just been told its write succeeded.
        val f = Fixture()
        f.channel.pipeline.notifyInactive()

        f.writeSomething()

        assertEquals(
            1,
            f.logger.warnings.count { "outbound message dropped" in it },
            "the first dropped message is named at warn: ${f.logger.warnings}",
        )

        f.writeSomething()

        assertEquals(
            1,
            f.logger.warnings.count { "outbound message dropped" in it },
            "and the burst that follows does not bury it",
        )
    }

    @Test
    fun `a connection that ends and then switches protocol gives each thing back once`() {
        val f = Fixture()
        f.feedPartialRecord()

        // Both routes, in the order they would run on a connection whose
        // protocol was switched after it ended. Neither may undo the other:
        // releasing the record twice is a refcount below zero, and the
        // tracker counts it.
        f.channel.pipeline.notifyInactive()
        f.channel.pipeline.remove(TLS)

        assertEquals(1, f.codec.closeCount, "the session is given back once, not once per route")
        assertEquals(0, f.tracker.outstandingCount, "and so is the record")
    }

    private companion object {
        const val TLS = "tls"
        const val SESSION_REFUSED = "the session refused to close"
        const val DEADLINE_REFUSED = "the deadline refused to cancel"
    }
}
