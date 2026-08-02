package io.github.fukusaka.keel.engine.nio

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
 * `engine-nio` under the default [IdleReadPolicy.DETECT_PEER_CLOSE] — the
 * policy whose `readEnabled = false` deliberately does NOT stop inbound
 * delivery. The pause must stop consumption anyway: bytes sent while
 * paused stay in kernel `rcvbuf` (no delivery, no loss) and arrive after
 * [IoTransport.resumeReads].
 *
 * Same harness shape as [NioIdleReadPolicyTest]: an engine-connected
 * loopback pair with direct transport access via
 * [AbstractPipelinedChannel.transport].
 */
class NioPauseReadsTest {

    @Test
    fun `bytes sent while paused are not delivered and arrive after resume under DETECT_PEER_CLOSE`() = runBlocking {
        val engine = NioEngine(
            IoEngineConfig(idleReadPolicy = IdleReadPolicy.DETECT_PEER_CLOSE),
        )
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port
        val client = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        try {
            val transport = (client as AbstractPipelinedChannel).transport
            val received = StringBuilder()
            var firstDelivery = CompletableDeferred<Unit>()
            transport.onRead = { buf ->
                val bytes = ByteArray(buf.readableBytes)
                buf.readByteArray(bytes, 0, bytes.size)
                buf.release()
                received.append(bytes.decodeToString())
                firstDelivery.complete(Unit)
            }
            transport.readEnabled = true

            // Flowing baseline: one delivery arrives normally.
            serverCh.write(
                io.github.fukusaka.keel.buf.DefaultAllocator.allocate(1).also { it.writeByte('a'.code.toByte()) },
            )
            serverCh.flush()
            withTimeout(IO_TIMEOUT_S.seconds) { firstDelivery.await() }
            assertEquals("a", received.toString())

            // Pause, then send while paused: the byte must NOT be delivered
            // within the negative window (it sits in kernel rcvbuf — the
            // one-shot OP_READ interest is consumed without a re-arm).
            transport.pauseReads()
            firstDelivery = CompletableDeferred()
            serverCh.write(
                io.github.fukusaka.keel.buf.DefaultAllocator.allocate(1).also { it.writeByte('b'.code.toByte()) },
            )
            serverCh.flush()
            delay(PAUSE_WINDOW_MS)
            assertEquals("a", received.toString(), "a paused transport must not deliver under DETECT_PEER_CLOSE")

            // Resume: the retained byte arrives — nothing was lost.
            transport.resumeReads()
            withTimeout(IO_TIMEOUT_S.seconds) { firstDelivery.await() }
            assertEquals("ab", received.toString(), "resume must deliver the bytes retained while paused")
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
         * Negative-assertion window for "not delivered while paused".
         * Generous against loopback latency (microseconds) without
         * slowing the suite; a regression (delivery while paused) turns
         * this into a deterministic failure, not a flake.
         */
        private const val PAUSE_WINDOW_MS = 300L
    }
}
