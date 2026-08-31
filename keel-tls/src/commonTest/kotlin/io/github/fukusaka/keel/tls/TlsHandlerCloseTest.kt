package io.github.fukusaka.keel.tls

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a TLS handler gives back when the connection it protects ends.
 *
 * The session is not garbage: on the native backends it is an `SSL*`, its
 * `BIO`, and a manually allocated context, and nothing reclaims those but
 * [TlsCodec.close]. This handler used to call it from two places that a
 * connection ending does not reach — the handler being taken out of the
 * pipeline for a protocol switch, and an `onClose` no channel ever sent — so
 * an ordinary TLS connection ended without any of it being given back.
 *
 * `onClose` reaches handlers now, and these pin what this one does with it.
 * They ask about the session, the buffer holding a half-read record, and what
 * happens when both of the routes run — which is the ordinary case, in that
 * order, on a connection whose protocol was switched.
 */
class TlsHandlerCloseTest {

    private val logger = PrintLogger("tls-close-test")

    /**
     * Counts what it was asked to give back, and needs more input for
     * anything it is given — which is what leaves a partial record behind in
     * the handler.
     */
    private class CountingCodec : TlsCodec {
        var closeCount: Int = 0
            private set

        override var isHandshakeComplete: Boolean = true
            private set
        override val negotiatedProtocol: String? = null
        override val peerCertificates: List<ByteArray> = emptyList()

        override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult =
            TlsCodecResult(TlsResult.NEED_MORE_INPUT, 0, 0)

        override fun protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult =
            TlsCodecResult(TlsResult.OK, 0, 0)

        override fun close() {
            closeCount++
        }
    }

    private class Fixture {
        val tracker = TrackingAllocator()
        val transport = TestIoTransport(tracker)
        val codec = CountingCodec()
        val handler = TlsHandler(codec)
        val logger = PrintLogger("tls-close-test")
        val channel = object : AbstractPipelinedChannel(transport, logger) {}

        init {
            channel.pipeline.addLast(TLS_HANDLER_NAME, handler)
        }

        /**
         * Leaves a half-read record in the handler: the codec consumes none
         * of this and asks for more, so the handler keeps the remainder for
         * the read that never comes.
         */
        fun feedPartialRecord() {
            val buf = tracker.allocate(8)
            repeat(4) { buf.writeByte(0x16) }
            channel.pipeline.notifyRead(buf)
        }
    }

    @Test
    fun `closing the channel gives back the TLS session`() {
        val f = Fixture()

        f.channel.close()

        assertEquals(
            1,
            f.codec.closeCount,
            "the session is released on the way out — on a native backend this is an SSL*, its BIO " +
                "and a manual allocation that nothing else reclaims",
        )
    }

    @Test
    fun `closing the channel gives back the buffer holding a half-read record`() {
        val f = Fixture()
        f.feedPartialRecord()
        assertTrue(
            f.tracker.outstandingCount > 0,
            "the handler is holding the remainder, which is what makes this worth asking about",
        )

        f.channel.close()

        assertEquals(
            0,
            f.tracker.outstandingCount,
            "and it is pooled, so it is given back with the session rather than only when the handler " +
                "is removed",
        )
    }

    @Test
    fun `a channel that closes and then switches protocol gives each thing back once`() {
        val f = Fixture()
        f.feedPartialRecord()

        // Both routes, in the order a protocol switch after a close would run
        // them. Neither may undo the other: releasing the record twice is a
        // refcount below zero, and the tracker counts it.
        f.channel.close()
        f.channel.pipeline.remove(TLS_HANDLER_NAME)

        assertEquals(2, f.codec.closeCount, "the session is asked twice, which its contract allows")
        assertEquals(0, f.tracker.outstandingCount, "the record is given back once")
    }

    private companion object {
        const val TLS_HANDLER_NAME = "tls"
    }
}
