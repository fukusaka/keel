package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.native.posix.PosixRawClient
import io.github.fukusaka.keel.native.posix.ReadResult
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import platform.posix.usleep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for the read-side idle (no-progress) timeout — the time-axis
 * defence against silent / stalled peers ([IoEngineConfig.idleTimeoutMillis]).
 *
 * Real sockets and real wall-clock timing, so each test is wrapped in a
 * `withTimeout` envelope (15 s) per the testing standard. The idle timeout itself
 * is small ([IDLE_MS]) so the closing case resolves well inside the envelope. Uses
 * a minimal echo handler rather than the HTTP codec to keep the dependency surface
 * small.
 */
@OptIn(ExperimentalForeignApi::class)
class KqueueEngineIdleTimeoutTest {

    /** Writes every inbound buffer straight back to the peer. */
    private class EchoHandler : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            ctx.propagateWriteAndFlush(msg)
        }
    }

    @Test
    fun `a silent client is closed after the idle timeout`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = KqueueEngine(IoEngineConfig(threads = 1, idleTimeoutMillis = IDLE_MS))
            val server = engine.bindPipeline("127.0.0.1", SILENT_PORT) { channel ->
                channel.pipeline.addLast("echo", EchoHandler())
            }
            usleep(SERVER_START_US)
            val clientFd = connectRawClient(SILENT_PORT)
            // Send nothing. The server must idle-close within ~IDLE_MS, so a single
            // read observes EOF rather than blocking until the 5 s SO_RCVTIMEO.
            val result = PosixRawClient.rawReadOnce(clientFd, 64, 5.seconds)
            assertEquals(ReadResult.Eof, result, "server should idle-close a silent client; got $result")
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun `a client active within the idle window is not closed`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = KqueueEngine(IoEngineConfig(threads = 1, idleTimeoutMillis = IDLE_MS))
            val server = engine.bindPipeline("127.0.0.1", ACTIVE_PORT) { channel ->
                channel.pipeline.addLast("echo", EchoHandler())
            }
            usleep(SERVER_START_US)
            val clientFd = connectRawClient(ACTIVE_PORT)
            // Send two halves separated by a gap shorter than IDLE_MS. Each half
            // refreshes the deadline, so the connection survives the gap and echoes
            // both back; reading the full 4 bytes proves it was never idle-closed.
            rawWrite(clientFd, "PI")
            usleep(GAP_US) // < IDLE_MS
            rawWrite(clientFd, "NG")
            val echo = PosixRawClient.rawRead(clientFd, 4)
            assertEquals("PING", echo, "active client should be echoed, not idle-closed")
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    private companion object {
        const val IDLE_MS = 500L
        const val SERVER_START_US: UInt = 100_000u
        const val GAP_US: UInt = 300_000u // 300 ms < IDLE_MS (500 ms)
        const val SILENT_PORT = 19891
        const val ACTIVE_PORT = 19892
    }
}
