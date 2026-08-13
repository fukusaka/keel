@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.native.posix.PosixRawClient
import io.github.fukusaka.keel.native.posix.ReadResult
import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
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

    /**
     * On every inbound message, writes a large chunk back — big enough that a peer
     * which stops reading fills its receive window and stalls the server's write
     * (arming the write-idle timer). Content is irrelevant for the timing test.
     */
    private class BigChunkWriter(private val onClose: CompletableDeferred<Unit>) : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg is IoBuf) msg.release()
            val out = ctx.allocator.allocate(CHUNK_BYTES)
            out.writerIndex = CHUNK_BYTES // expose CHUNK_BYTES readable bytes (content unset)
            ctx.propagateWriteAndFlush(out)
        }

        // The write-idle timeout force-closes the channel, which surfaces here as
        // onInactive. Observing the close server-side avoids a client read that would
        // advance the pending write and refresh/cancel the write-idle timer.
        override fun onInactive(ctx: PipelineHandlerContext) {
            onClose.complete(Unit)
        }
    }

    @Test
    fun `a silent client is closed after the idle timeout`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = KqueueEngine(IoEngineConfig(threads = 1, idleTimeoutMillis = IDLE_MS))
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

    @Test
    fun `a slow-read client is closed by the write idle timeout while reads stay active`() = runBlocking {
        withTimeout(15.seconds) {
            val serverClosed = CompletableDeferred<Unit>()
            val engine = KqueueEngine(IoEngineConfig(threads = 1, idleTimeoutMillis = IDLE_MS))
            val server = engine.bindPipeline("127.0.0.1", SLOW_READ_PORT) { channel ->
                channel.pipeline.addLast("big", BigChunkWriter(serverClosed))
            }
            usleep(SERVER_START_US)
            val clientFd = connectRawClient(SLOW_READ_PORT)
            // Trigger a large response, then never read it: the server's write stalls and
            // arms the write-idle timer at the first stalled write. Trickle a byte every
            // TRICKLE_US (< IDLE_MS) so the read side stays refreshed and the write-idle
            // timer — armed earlier than read-idle — is the one that fires.
            //
            // Observe the close on the SERVER side (BigChunkWriter.onInactive → the
            // deferred), never by reading on the client. A client read would advance the
            // server's stalled response, and on flush progress `updatePendingBytes`
            // touches (or cancels) the write-idle timer — so any read before the timer
            // fires keeps refreshing it and the close is never observed (the flaky
            // `closed == false` on a loaded CI runner).
            repeat(4) {
                runCatching { rawWrite(clientFd, "x") }
                usleep(TRICKLE_US)
            }
            // write-idle must force-close the connection; onInactive completes the
            // deferred. The withTimeout envelope fails the test if it never fires.
            serverClosed.await()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    private companion object {
        // Generous idle window: a loaded CI macOS runner can delay a usleep / timer
        // dispatch by a few hundred ms, so the gaps and trickles that must stay under
        // IDLE_MS keep a comfortable margin (was 500 ms, which left only ~200 ms).
        const val IDLE_MS = 1000L
        const val SERVER_START_US: UInt = 100_000u
        const val GAP_US: UInt = 300_000u // 300 ms < IDLE_MS keeps the active client alive across the gap
        const val TRICKLE_US: UInt = 160_000u // 160 ms < IDLE_MS keeps read-idle refreshed
        const val CHUNK_BYTES = 1 shl 20 // 1 MiB per response — exceeds the socket buffer
        const val SILENT_PORT = 19891
        const val ACTIVE_PORT = 19892
        const val SLOW_READ_PORT = 19895
    }
}
