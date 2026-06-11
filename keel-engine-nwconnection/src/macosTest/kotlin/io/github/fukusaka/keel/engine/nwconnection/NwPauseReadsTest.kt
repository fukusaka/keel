package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.IdleReadPolicy
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies the flow-control pause contract ([IoTransport.pauseReads]) for
 * `engine-nwconnection` under the default
 * [IdleReadPolicy.DETECT_PEER_CLOSE] — the policy whose
 * `readEnabled = false` deliberately does NOT stop the receive loop.
 * The pause must stop it anyway: the in-flight receive may deliver once
 * (the bounded overshoot the contract allows), bytes sent afterwards
 * stay in the framework receive buffer (no delivery, no loss), and they
 * arrive after [IoTransport.resumeReads]. Mirror of [NioPauseReadsTest]
 * in `engine-nio`.
 */
class NwPauseReadsTest {

    @Test
    fun `bytes sent while paused are not delivered and arrive after resume under DETECT_PEER_CLOSE`() = runBlocking {
        val engine = NwEngine(
            IoEngineConfig(idleReadPolicy = IdleReadPolicy.DETECT_PEER_CLOSE),
        )
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port
        val client = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        try {
            val transport = (client as AbstractPipelinedChannel).transport
            val received = StringBuilder()
            var delivery = CompletableDeferred<Unit>()
            transport.onRead = { buf ->
                val bytes = ByteArray(buf.readableBytes)
                buf.readByteArray(bytes, 0, bytes.size)
                buf.release()
                received.append(bytes.decodeToString())
                delivery.complete(Unit)
            }
            transport.readEnabled = true

            // Flowing baseline: one delivery arrives normally.
            serverCh.write(DefaultAllocator.allocate(1).also { it.writeByte('a'.code.toByte()) })
            serverCh.flush()
            withTimeout(IO_TIMEOUT_S.seconds) { delivery.await() }
            assertEquals("a", received.toString())

            // Pause. NWConnection cannot un-arm the receive that is
            // already posted, so the contract's bounded overshoot here is
            // exactly one delivery: the next write completes the
            // outstanding receive.
            transport.pauseReads()
            delivery = CompletableDeferred()
            serverCh.write(DefaultAllocator.allocate(1).also { it.writeByte('b'.code.toByte()) })
            serverCh.flush()
            withTimeout(IO_TIMEOUT_S.seconds) { delivery.await() }
            assertEquals("ab", received.toString(), "the outstanding receive is the allowed overshoot")

            // After the overshoot no new receive is armed (armRead is a
            // no-op while paused): the next byte must NOT be delivered
            // within the negative window — the framework buffer retains it.
            delivery = CompletableDeferred()
            serverCh.write(DefaultAllocator.allocate(1).also { it.writeByte('c'.code.toByte()) })
            serverCh.flush()
            delay(PAUSE_WINDOW_MS)
            assertEquals("ab", received.toString(), "beyond the overshoot a paused transport must not deliver")

            // Resume: the retained byte arrives — nothing was lost.
            transport.resumeReads()
            withTimeout(IO_TIMEOUT_S.seconds) { delivery.await() }
            assertEquals("abc", received.toString(), "resume must deliver the bytes retained while paused")
        } finally {
            client.close()
            server.close()
            engine.close()
        }
    }

    private companion object {
        /** Loopback dispatch budget, matching sibling engine tests. */
        private const val IO_TIMEOUT_S = 5

        /**
         * Negative-assertion window for "not delivered while paused";
         * generous against loopback latency without slowing the suite.
         */
        private const val PAUSE_WINDOW_MS = 300L
    }
}
