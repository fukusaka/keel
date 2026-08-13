@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import io.github.fukusaka.keel.buf.IoBuf
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
import kotlin.test.assertTrue
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
class EpollEngineIdleTimeoutTest {

    /** Writes every inbound buffer straight back to the peer. */
    private class EchoHandler : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            ctx.propagateWriteAndFlush(msg)
        }
    }

    /**
     * On every inbound message, writes a large chunk back — big enough that a peer
     * which stops reading fills its receive window and stalls the server's write
     * (arming the write-idle timer). Content is irrelevant for the timing test.
     */
    private class BigChunkWriter : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg is IoBuf) msg.release()
            val out = ctx.allocator.allocate(CHUNK_BYTES)
            out.writerIndex = CHUNK_BYTES // expose CHUNK_BYTES readable bytes (content unset)
            ctx.propagateWriteAndFlush(out)
        }
    }

    @Test
    fun `a silent client is closed after the idle timeout`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = EpollEngine(IoEngineConfig(threads = 1, idleTimeoutMillis = IDLE_MS))
            // Empty pipeline: reads are still enabled, and the idle timeout must
            // force-close even with no handler / bridge (it reclaims the fd in every
            // channel mode, unlike a cooperative peer-FIN).
            val server = engine.bindPipeline("127.0.0.1", SILENT_PORT) { }
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
            val engine = EpollEngine(IoEngineConfig(threads = 1, idleTimeoutMillis = IDLE_MS))
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

    @Test
    fun `a slow-read client is closed by the write idle timeout while reads stay active`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = EpollEngine(IoEngineConfig(threads = 1, idleTimeoutMillis = IDLE_MS))
            val server = engine.bindPipeline("127.0.0.1", SLOW_READ_PORT) { channel ->
                channel.pipeline.addLast("big", BigChunkWriter())
            }
            usleep(SERVER_START_US)
            val clientFd = connectRawClient(SLOW_READ_PORT)
            // Trigger a large response, then never read it: the server's write stalls
            // and arms the write-idle timer. Trickle a byte every TRICKLE_US (< IDLE_MS)
            // to keep the *read* side refreshed, so only the write-idle timer can fire.
            // The trickle tolerates the server closing mid-loop (a loaded runner may fire
            // write-idle before the loop ends).
            repeat(4) {
                runCatching { rawWrite(clientFd, "x") }
                usleep(TRICKLE_US)
            }
            // write-idle must reclaim the connection: draining observes a close — either
            // EOF or a reset (force-closing with buffered data sends RST). Had write-idle
            // not fired, the server would keep producing data as we drain and we would
            // never reach a close within the bounded window.
            var closed = false
            var reads = 0
            while (!closed && reads < MAX_DRAIN_READS) {
                reads++
                when (PosixRawClient.rawReadOnce(clientFd, DRAIN_CHUNK, 2.seconds)) {
                    ReadResult.Eof, is ReadResult.Failed -> closed = true
                    is ReadResult.Bytes -> Unit // buffered partial — keep draining
                    ReadResult.WouldBlock -> break
                }
            }
            assertTrue(closed, "write-idle should close (EOF/RST) a non-reading peer while reads stay active")
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    private companion object {
        const val IDLE_MS = 500L
        const val SERVER_START_US: UInt = 100_000u
        const val GAP_US: UInt = 300_000u // 300 ms < IDLE_MS (500 ms)
        const val TRICKLE_US: UInt = 160_000u // 160 ms < IDLE_MS keeps read-idle alive
        const val CHUNK_BYTES = 1 shl 20 // 1 MiB per response — exceeds the socket buffer
        const val DRAIN_CHUNK = 1 shl 16 // 64 KiB per drain read
        const val MAX_DRAIN_READS = 200 // bounded drain so a non-closing bug fails, not hangs
        const val SILENT_PORT = 19893
        const val ACTIVE_PORT = 19894
        const val SLOW_READ_PORT = 19896
    }
}
