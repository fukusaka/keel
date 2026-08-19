package io.github.fukusaka.keel.server.ktor.websocket

import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
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

    private class RecordingLogger : Logger {
        val records = mutableListOf<Pair<LogLevel, String>>()
        val warnings: List<String> get() = records.filter { it.first == LogLevel.WARN }.map { it.second }
        override fun isLoggable(level: LogLevel) = true
        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            records.add(level to message.toString())
        }
    }

    @Test
    fun `an error finishes the session's inbound and carries the reason`() = runTest(timeout = 15.seconds) {
        val log = RecordingLogger()
        val channel = object : AbstractPipelinedChannel(TestIoTransport(), log) {}
        val bridge = RawInboundBridge()
        channel.pipeline.addLast("raw", bridge)
        val cause = InjectedFault("peer is gone")

        channel.pipeline.notifyError(cause)

        val received = bridge.receiveCatching()
        assertTrue(received.isClosed, "the session has no more inbound to read")
        assertEquals(cause, received.exceptionOrNull(), "and it says why")
    }

    @Test
    fun `an error handled here does not travel on as unhandled`() = runTest(timeout = 15.seconds) {
        // Closing the inbound with the cause is handling it: the session this
        // bridge feeds is over, and the reason went with it. Passing it on as
        // well would reach the tail of a pipeline whose last handler this is,
        // which records what arrives there as an application bug -- on the
        // ordinary path where a peer disappears mid-write.
        val log = RecordingLogger()
        val channel = object : AbstractPipelinedChannel(TestIoTransport(), log) {}
        channel.pipeline.addLast("raw", RawInboundBridge())

        channel.pipeline.notifyError(InjectedFault("peer is gone"))

        assertTrue(
            log.warnings.none { "Unhandled" in it },
            "it was handled here: ${log.warnings}",
        )
    }
}
