package io.github.fukusaka.keel.server.ktor

import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.server.ktor.websocket.RawInboundBridge
import io.github.fukusaka.keel.testing.InjectedFault
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readByte
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Pins what the upgraded session reads off its inbound channel when the
 * connection ends — the difference between a peer that finished talking and
 * one the transport gave up on.
 */
class UpgradeInboundPumpTest {

    private fun bridgeOnChannel(): Pair<AbstractPipelinedChannel, RawInboundBridge> {
        val channel = object : AbstractPipelinedChannel(TestIoTransport(), PrintLogger("upgrade-pump-test")) {}
        val bridge = RawInboundBridge()
        channel.pipeline.addLast("raw", bridge)
        return channel to bridge
    }

    @Test
    fun `a connection the transport gave up on cancels the session's input with the reason`() =
        runTest(timeout = 15.seconds) {
            val (channel, bridge) = bridgeOnChannel()
            val input = ByteChannel(autoFlush = true)
            val pump = launch { pumpRawBridgeToInput(bridge, input) }

            channel.pipeline.notifyError(InjectedFault("peer is gone"))
            pump.join()

            // The session reads a closed channel either way; what tells the
            // two apart is what the closure carries.
            val failure = runCatching { input.readByte() }.exceptionOrNull()
            val carried = generateSequence(failure) { it.cause }.firstOrNull { it is InjectedFault }
            assertIs<InjectedFault>(carried, "the session is told why its inbound ended: $failure")
            assertEquals("peer is gone", carried.message)
        }

    @Test
    fun `a peer that finished talking ends the session's input with nothing to explain`() =
        runTest(timeout = 15.seconds) {
            val (_, bridge) = bridgeOnChannel()
            val input = ByteChannel(autoFlush = true)
            val pump = launch { pumpRawBridgeToInput(bridge, input) }

            bridge.close()
            pump.join()

            val failure = runCatching { input.readByte() }.exceptionOrNull()
            assertNull(
                failure?.cause,
                "reading past a clean end fails, but not because anything went wrong: $failure",
            )
        }
}
