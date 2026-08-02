package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private external fun setTimeout(callback: () -> Unit, delayMs: Int): dynamic
private external fun clearTimeout(handle: dynamic)

/**
 * Integration tests for the read- and write-side idle (no-progress) timeout on the
 * Node.js engine ([IoEngineConfig.idleTimeoutMillis]) — the time-axis defence
 * against silent / stalled / slow-read peers.
 *
 * The timeout runs at the keel transport seam backed by [NodeEventLoopTimer] (Node
 * `setTimeout` on the libuv event loop). The peer is a **raw, non-reading** Node
 * `net.Socket` so the test can withhold reads (forcing the server's write to stall)
 * and the keel server force-closes the idle connection itself.
 *
 * Timing is real wall-clock: `runTest`'s virtual `delay` would skip the real
 * intervals the `setTimeout`-based timer measures, so [realDelay] suspends on a
 * real `setTimeout` and the `runTest(timeout = …)` envelope (real-time) bounds the
 * whole test.
 */
class NodeEngineIdleTimeoutTest {

    /** Writes every inbound buffer straight back to the peer. */
    private class EchoHandler : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            ctx.propagateWriteAndFlush(msg)
        }
    }

    @Test
    fun `a silent client is closed after the idle timeout`() = runTest(timeout = 25.seconds) {
        val engine = NodeEngine(IoEngineConfig(threads = 1, idleTimeoutMillis = IDLE_MS))
        // Empty pipeline: reads are still enabled, and the idle timeout must
        // force-close even with no handler (it reclaims the connection in every mode).
        // Node's bindPipeline needs a fixed port (it assigns ephemeral ports async).
        val server = engine.bindPipeline("127.0.0.1", SILENT_PORT) { }
        val port = (server.localAddress as InetSocketAddress).port
        val client = rawConnect(port)
        // Send nothing → the server force-closes within ~IDLE_MS → 'close' fires.
        assertTrue(awaitClose(client, CLOSE_WAIT_MS), "server should idle-close a silent client")
        client.destroy()
        server.close()
        engine.close()
    }

    @Test
    fun `a client active within the idle window is not closed`() = runTest(timeout = 25.seconds) {
        // A generous timeout vs. a small trickle gap (≈13×) so event-loop / GC jitter
        // cannot stretch a single inter-write gap past the timeout (spurious close).
        val engine = NodeEngine(IoEngineConfig(threads = 1, idleTimeoutMillis = ACTIVE_IDLE_MS))
        val server = engine.bindPipeline("127.0.0.1", ACTIVE_PORT) { channel ->
            channel.pipeline.addLast("echo", EchoHandler())
        }
        val port = (server.localAddress as InetSocketAddress).port
        val client = rawConnect(port)
        // Trickle a byte every ACTIVE_GAP_MS (<< ACTIVE_IDLE_MS) for longer than the
        // timeout. Each read on the server refreshes its read-idle deadline, so the
        // connection must NOT be idle-closed even though activity spans the timeout.
        repeat(16) { // 16 × 150 ms = 2400 ms > ACTIVE_IDLE_MS (2000 ms)
            client.write("x")
            realDelay(ACTIVE_GAP_MS)
        }
        // Still open: no 'close' within a short observation window.
        assertFalse(awaitClose(client, NOT_CLOSED_WAIT_MS), "connection was idle-closed despite read activity")
        client.destroy()
        server.close()
        engine.close()
    }

    /**
     * Writes a large chunk back per inbound message (stalling the write to a
     * non-reading peer, which arms the write-idle timer) and completes [closed]
     * when the channel is force-closed. The server-side observation sidesteps
     * Node's paused-socket caveat: a non-reading client with a full receive buffer
     * would not observe the peer's FIN/RST until it drained, but draining would
     * clear the very back-pressure under test — so the close is observed here.
     */
    private class BigChunkWriterClosing(private val closed: CompletableDeferred<Unit>) : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg is IoBuf) msg.release()
            val out = ctx.allocator.allocate(CHUNK_BYTES)
            out.writerIndex = CHUNK_BYTES
            ctx.propagateWriteAndFlush(out)
        }

        override fun onInactive(ctx: PipelineHandlerContext) {
            closed.complete(Unit)
            ctx.propagateInactive()
        }
    }

    @Test
    fun `a slow-read client is closed by the write idle timeout while reads stay active`() = runTest(
        timeout = 25.seconds,
    ) {
        val engine = NodeEngine(IoEngineConfig(threads = 1, idleTimeoutMillis = IDLE_MS))
        val closed = CompletableDeferred<Unit>()
        val server = engine.bindPipeline("127.0.0.1", SLOW_READ_PORT) { channel ->
            channel.pipeline.addLast("big", BigChunkWriterClosing(closed))
        }
        val client = rawConnect(SLOW_READ_PORT) // paused: never reads → the server's write stalls
        // Trigger a large response and never read it (the server's write stalls,
        // arming the write-idle timer). Trickle a byte every GAP_MS (< IDLE_MS) to
        // keep the server's read side active during the response window.
        repeat(4) {
            client.write("x")
            realDelay(GAP_MS)
        }
        // The server keeps the unread response buffered; once the write makes no
        // progress for the timeout it force-closes the connection, firing onInactive.
        // Poll the server-side close flag in real time (bounded so a non-closing bug
        // fails fast rather than hanging on the 25 s envelope).
        var waited = 0
        while (!closed.isCompleted && waited < CLOSE_WAIT_MS) {
            realDelay(POLL_MS)
            waited += POLL_MS
        }
        assertTrue(closed.isCompleted, "write-idle should force-close a slow-read peer while reads stay active")
        client.destroy()
        server.close()
        engine.close()
    }

    /** Suspends [ms] real milliseconds (a real `setTimeout`, not `runTest`'s virtual clock). */
    private suspend fun realDelay(ms: Int) = suspendCancellableCoroutine<Unit> { cont ->
        val handle = setTimeout({ cont.resume(Unit) }, ms)
        cont.invokeOnCancellation { clearTimeout(handle) }
    }

    /**
     * Opens a raw Node TCP client that does not auto-read (no `'data'` listener →
     * paused mode), so the test controls whether the server's writes are drained.
     * An `'error'` listener swallows `ECONNRESET` so a server-side force-close does
     * not crash the test with an unhandled `'error'` event.
     */
    private fun rawConnect(port: Int): dynamic {
        val net = js("require('net')")
        val sock = net.connect(port, "127.0.0.1")
        sock.on("error") { _: dynamic -> } // swallow ECONNRESET on force-close
        return sock
    }

    /**
     * Resolves `true` when the socket closes within [timeoutMs], else `false`.
     * Checks [Socket.destroyed] first so a close that already happened (e.g. the
     * server force-closed while the caller was mid-trickle, before this listener
     * was registered) is not missed.
     */
    private suspend fun awaitClose(sock: dynamic, timeoutMs: Int): Boolean {
        if (sock.destroyed == true) return true
        return suspendCancellableCoroutine { cont ->
            var done = false
            val timer = setTimeout({
                if (!done) {
                    done = true
                    cont.resume(false)
                }
            }, timeoutMs)
            sock.on("close") { _: dynamic ->
                if (!done) {
                    done = true
                    clearTimeout(timer)
                    cont.resume(true)
                }
            }
            cont.invokeOnCancellation { clearTimeout(timer) }
        }
    }

    private companion object {
        const val IDLE_MS = 500L
        const val GAP_MS = 160 // < IDLE_MS keeps the read-idle deadline alive
        const val CLOSE_WAIT_MS = 4_000 // generous upper bound for the server to idle-close
        const val ACTIVE_IDLE_MS = 2_000L // generous vs. ACTIVE_GAP_MS for jitter tolerance
        const val ACTIVE_GAP_MS = 150 // ≈13× under ACTIVE_IDLE_MS
        const val NOT_CLOSED_WAIT_MS = 400 // short window to confirm the connection stayed open
        const val CHUNK_BYTES = 1 shl 20 // 1 MiB per response — exceeds the socket buffer
        const val POLL_MS = 100 // server-side close poll interval (slow-read test)
        const val SILENT_PORT = 19920
        const val ACTIVE_PORT = 19921
        const val SLOW_READ_PORT = 19922
    }
}
