package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.native.posix.PosixRawClient
import io.github.fukusaka.keel.native.posix.ReadResult
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
 * Integration tests for the read- and write-side idle (no-progress) timeout on the
 * NWConnection engine ([IoEngineConfig.idleTimeoutMillis]) — the time-axis defence
 * against silent / stalled / slow-read peers.
 *
 * Real sockets and real wall-clock timing, so each test is wrapped in a
 * `withTimeout` envelope per the testing standard. The timeout runs at the keel
 * transport seam backed by [NwEventLoopTimer] (a GCD `dispatch_after` on the
 * connection's serial dispatch queue), so the timer fires in FIFO order with the
 * connection's own read / write completion callbacks. The peer side is a plain
 * POSIX socket ([PosixRawClient]) — no NWConnection FFI on the client — and the
 * server force-closes the idle connection itself, so the test exercises the engine
 * path rather than a raw blocking-syscall loop. A minimal echo / big-chunk handler
 * is used rather than the HTTP codec.
 *
 * **Timing budgets are deliberately generous.** The macosArm64 suite runs on
 * loaded CI runners where a GCD `dispatch_after` fires well after its nominal
 * deadline; the local macOS gate is the authoritative fast-timing check. So
 * [IDLE_MS] is large enough that an active client's inter-write gap cannot stretch
 * past it under scheduling jitter, the silent read waits up to [CLOSE_BUDGET] (many
 * multiples of [IDLE_MS]) for the close, and the slow-read close is observed
 * server-side via `onInactive` rather than by racing a client-side read against the
 * timer.
 */
@OptIn(ExperimentalForeignApi::class)
class NwEngineIdleTimeoutTest {

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
        withTimeout(ENVELOPE) {
            val engine = NwEngine(IoEngineConfig(threads = 1, idleTimeoutMillis = IDLE_MS))
            // Empty pipeline: reads are still enabled, and the idle timeout must
            // force-close even with no handler / bridge (it reclaims the fd in every
            // channel mode, unlike a cooperative peer-FIN).
            val server = engine.bindPipeline("127.0.0.1", 0) { }
            val port = (server.localAddress as InetSocketAddress).port
            val clientFd = connectRawClient(port)
            // Send nothing. The server must idle-close; a single blocking read observes
            // the EOF once the idle timeout fires. The read budget is generous (>>
            // IDLE_MS) so a loaded runner's late GCD timer still resolves inside it. The
            // server writes nothing, so the read never refreshes any timer.
            val result = PosixRawClient.rawReadOnce(clientFd, READ_CHUNK, CLOSE_BUDGET)
            assertEquals(ReadResult.Eof, result, "server should idle-close a silent client; got $result")
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun `a client active within the idle window is not closed`() = runBlocking {
        withTimeout(ENVELOPE) {
            val engine = NwEngine(IoEngineConfig(threads = 1, idleTimeoutMillis = IDLE_MS))
            val server = engine.bindPipeline("127.0.0.1", 0) { channel ->
                channel.pipeline.addLast("echo", EchoHandler())
            }
            val port = (server.localAddress as InetSocketAddress).port
            val clientFd = connectRawClient(port)
            // Send two halves separated by a gap far shorter than IDLE_MS — a wide
            // margin so a loaded runner's scheduling jitter cannot stretch the gap
            // past the deadline. Each half refreshes the deadline, so the connection
            // survives the gap and echoes both back; reading the full 4 bytes proves
            // it was never idle-closed.
            rawWrite(clientFd, "PI")
            usleep(GAP_US) // << IDLE_MS
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
        withTimeout(ENVELOPE) {
            val serverClosed = CompletableDeferred<Unit>()
            val engine = NwEngine(IoEngineConfig(threads = 1, idleTimeoutMillis = IDLE_MS))
            val server = engine.bindPipeline("127.0.0.1", 0) { channel ->
                channel.pipeline.addLast("big", BigChunkWriter(serverClosed))
            }
            val port = (server.localAddress as InetSocketAddress).port
            val clientFd = connectRawClient(port)
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
            // `closed == false` on a loaded CI runner). The trickle is bounded and stays
            // SIGPIPE-safe (rawConnect sets SO_NOSIGPIPE) even if a write lands after the
            // force-close.
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
        // Large idle window: on a loaded CI runner an active client's inter-write
        // gap (GAP_US / TRICKLE_US) must not stretch past it, and the GCD timer has
        // room to fire late without the silent read's budget expiring first.
        const val IDLE_MS = 2000L
        const val GAP_US: UInt = 300_000u // 300 ms — << IDLE_MS (2 s), wide margin for jitter
        const val TRICKLE_US: UInt = 500_000u // 500 ms < IDLE_MS keeps the read-idle timer alive
        const val CHUNK_BYTES = 1 shl 20 // 1 MiB per response — exceeds the socket buffer
        const val READ_CHUNK = 1 shl 16 // 64 KiB read buffer for the silent-close read
        val ENVELOPE = 30.seconds // withTimeout envelope — covers CLOSE_BUDGET + setup with margin
        val CLOSE_BUDGET = 15.seconds // wait for the silent idle-close up to this long (>> IDLE_MS)
    }
}
