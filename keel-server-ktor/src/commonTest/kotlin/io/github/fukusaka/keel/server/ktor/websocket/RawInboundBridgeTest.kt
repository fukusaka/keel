package io.github.fukusaka.keel.server.ktor.websocket

import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.testing.InjectedFault
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Pins what the upgraded session's inbound bridge does with an error the
 * pipeline delivers — now that a transport reports a refused send there.
 */
class RawInboundBridgeTest {

    @Test
    fun `an error finishes the session's inbound and carries the reason`() = runTest(timeout = 15.seconds) {
        val channel = object : AbstractPipelinedChannel(TestIoTransport(), PrintLogger("ws-bridge-test")) {}
        val bridge = RawInboundBridge()
        channel.pipeline.addLast("raw", bridge)
        val cause = InjectedFault("peer is gone")

        channel.pipeline.notifyError(cause)

        val received = bridge.receiveCatching()
        assertTrue(received.isClosed, "the session has no more inbound to read")
        assertEquals(cause, received.exceptionOrNull(), "and it says why")
    }
}
