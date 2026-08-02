package io.github.fukusaka.keel.server.ktor

import io.github.fukusaka.keel.buf.withTracking
import io.github.fukusaka.keel.engine.netty.NettyEngine
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.ClosedWriteChannelException
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for [KeelByteWriteChannel]'s close-cause wrap policy:
 *
 * - `wrapClosedCause` policy mirrors Ktor's `CloseToken.wrapCause`,
 *   wrapping arbitrary throwables as [ClosedWriteChannelException] with
 *   the original as `cause`.
 * - `closedCause` getter returns a fresh wrapper instance per access so
 *   identity comparisons / `addSuppressed` accumulation cannot
 *   cross-contaminate.
 *
 * The pre-fix code was `if (cause != null) throw cause`, which would
 * throw the raw `IllegalStateException` directly. The post-fix code goes
 * through `wrapClosedCause(...)` and throws a fresh
 * `ClosedWriteChannelException(cause)`. Pre-fix, [`flush after cancel
 * throws ClosedWriteChannelException wrapping the user cause`] fails
 * (raw cause type leaks through); post-fix it passes — Red-Green
 * regression check for the wrap policy.
 */
class KeelByteWriteChannelTest {

    @Test
    fun `flush after cancel throws ClosedWriteChannelException wrapping the user cause`() {
        val cause = IllegalStateException("simulated abort")
        val observed = AtomicReference<Throwable?>(null)

        withKeelServer({
            routing {
                get("/cancel-then-flush") {
                    call.respondBytesWriter {
                        writeFully("first".encodeToByteArray())
                        flush()
                        cancel(cause)
                        try {
                            // Pre-fix this would throw `cause` (IllegalStateException).
                            // Post-fix it throws ClosedWriteChannelException(cause).
                            flush()
                            fail("expected flush() to throw after cancel")
                        } catch (e: Throwable) {
                            observed.set(e)
                            // Re-throw so the response cycle terminates cleanly.
                            throw e
                        }
                    }
                }
            }
        }) { port ->
            // Trigger the route and consume what we can — body may be partial.
            runCatching { httpGet(port, "/cancel-then-flush") }
        }

        val exception = observed.get()
        assertNotNull(exception, "expected flush() to throw after cancel; nothing observed")
        assertTrue(
            exception is ClosedWriteChannelException,
            "expected ClosedWriteChannelException, got ${exception::class.simpleName}: $exception",
        )
        assertSame(cause, exception.cause, "wrapper should preserve the user-supplied cause")
    }

    @Test
    fun `closedCause yields a fresh wrapper per access`() {
        val cause = IllegalStateException("frozen channel")
        val first = AtomicReference<Throwable?>(null)
        val second = AtomicReference<Throwable?>(null)

        withKeelServer({
            routing {
                get("/closed-cause-identity") {
                    call.respondBytesWriter {
                        writeFully("hello".encodeToByteArray())
                        flush()
                        cancel(cause)
                        // The contract: every read of `closedCause`
                        // returns a fresh wrapper. Throwing the same
                        // Throwable instance repeatedly mutates its
                        // stack trace and lets `addSuppressed`
                        // accumulate, so Ktor's CloseToken creates a
                        // new wrapper each time `closedCause` is read
                        // (CloseToken.wrapCause). Mirror it here.
                        first.set(closedCause)
                        second.set(closedCause)
                        // Stop the writer cleanly so the response pipeline
                        // can finalise the connection.
                        runCatching { flush() }
                    }
                }
            }
        }) { port ->
            runCatching { httpGet(port, "/closed-cause-identity") }
        }

        val a = first.get()
        val b = second.get()
        assertNotNull(a)
        assertNotNull(b)
        assertNotSame(a, b, "closedCause should return a fresh wrapper per access")
        assertSame(cause, a.cause)
        assertSame(cause, b.cause)
        assertTrue(a is ClosedWriteChannelException)
        assertTrue(b is ClosedWriteChannelException)
    }

    /**
     * Cancel-without-rethrow regression test: [KeelByteWriteChannel.cancel] without a re-throw must not leave
     * the keep-alive loop running with the encoder still in `CHUNKED` mode.
     *
     * **Scenario**: a handler calls `cancel(cause)` inside [io.ktor.server.response.respondBytesWriter]
     * without re-throwing, so [engine.pipeline.execute] returns normally.
     * [AbstractPipelinedWriteChannel.cancel] completes [terminationDeferred] immediately without
     * writing [io.github.fukusaka.keel.codec.http.HttpBodyEnd], leaving the
     * [io.github.fukusaka.keel.codec.http.HttpResponseEncoder] in `CHUNKED` mode.
     *
     * **Pre-fix (Red)**: [KeelApplicationResponse.writeChannelCancelled] property was absent.
     * [processRequest][KeelCodecConnectionHandler] returned `keepAlive = true`, the loop read
     * the next request (incrementing [sentinelInvoked]), and then the encoder's
     * `check(streamingMode == NONE)` threw — connection closed with "Connection handling failed".
     *
     * **Post-fix (Green)**: `writeChannelCancelled` returns `true` → `processRequest` returns
     * `false` → loop exits before ever reading the second request → [sentinelInvoked] stays `false`.
     *
     * Red-Green verification: run with the `if (call.response.writeChannelCancelled) return false`
     * line in [KeelCodecConnectionHandler.processRequest] commented out and confirm the assertion
     * fails; restore it and confirm it passes.
     */
    @Test
    fun `cancel without rethrow closes keep-alive connection before next request`() {
        val sentinelInvoked = AtomicBoolean(false)

        withKeelServer({
            routing {
                get("/cancel-swallowed") {
                    call.respondBytesWriter {
                        writeFully("data".encodeToByteArray())
                        flush()
                        // Cancel without rethrowing: models explicit early termination such as
                        // SSE handlers that catch client-disconnection and exit cleanly.
                        // Ktor's exception path in respondWriteChannelContent also calls
                        // cancel(cause) before rethrowing, but in that case the exception
                        // propagates through engine.pipeline.execute() and the keep-alive
                        // guard is bypassed — this test covers the no-rethrow variant.
                        cancel(IOException("simulated client disconnect"))
                    }
                }
                get("/sentinel") {
                    // This handler must NOT be reached: after cancel()-without-rethrow the
                    // connection must be closed before the keep-alive loop can read a next head.
                    sentinelInvoked.set(true)
                    call.respondText("sentinel")
                }
            }
        }, keepAlive = true) { port ->
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 3_000
                val out = socket.getOutputStream()
                val inp = socket.getInputStream()

                // Send both requests back-to-back without waiting for the first response.
                // This ensures /sentinel bytes are already buffered when the server
                // decides whether to close the connection — making the Red-state path
                // deterministic: in Red, the loop reads and processes /sentinel before
                // the encoder crash; in Green, the loop exits before reading /sentinel.
                val req1 = "GET /cancel-swallowed HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n"
                val req2 = "GET /sentinel HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                out.write(req1.toByteArray() + req2.toByteArray())
                out.flush()

                // Drain until EOF. In both fix states this terminates promptly:
                //  Green: server detects writeChannelCancelled → closes connection.
                //  Red: server attempts /sentinel, encoder check fires, closes connection.
                val buf = ByteArray(4096)
                try {
                    while (inp.read(buf) != -1) { /* drain */ }
                } catch (_: java.net.SocketTimeoutException) {
                    // Guard against slow CI — assertion below still catches the Red state
                    // because sentinelInvoked is set before the encoder crash.
                }
            }

            // sentinelInvoked is set before the server tries to write the /sentinel response
            // (Red) or never at all (Green), so no further delay is needed.
            assertFalse(
                sentinelInvoked.get(),
                "cancel-without-rethrow: /sentinel handler was invoked — writeChannelCancelled check is absent or not firing; " +
                    "the encoder was left in CHUNKED mode after cancel()-without-rethrow",
            )
        }
    }

    /**
     * FIFO-ordering regression test: `terminate()` in [AbstractPipelinedWriteChannel] must dispatch
     * [writeTerminator] via [kotlinx.coroutines.CoroutineDispatcher.dispatch] rather than
     * `withContext`, so the terminator task is always enqueued after pending emit tasks —
     * even on Netty whose [io.github.fukusaka.keel.engine.netty.NettyEventLoopDispatcher]
     * returns `isDispatchNeeded() = false` when the caller is already on the EventLoop thread,
     * causing `withContext` to execute the block **inline** ahead of queued emit tasks.
     *
     * **Failure scenario (Red)**: A handler writes N-1 frames with explicit `flush()` and
     * writes a final (Nth) frame without flushing. Ktor's `respondWriteChannelContent` wraps
     * the user lambda in `withContext(Dispatchers.IOBridge)`, so frames 0..N-2 enqueue emit
     * tasks T1..T(N-1) from the IO thread — these land in the EventLoop queue before the
     * resume-parent task R. When `withContext(IOBridge)` returns the pipeline coroutine
     * resumes on the EventLoop and Ktor's `use{}` finally calls the deprecated
     * `ByteWriteChannel.close()` extension, which fires `flushAndClose()` on the EL thread via
     * `startCoroutineCancellable`. Inside `flushAndClose()`, `drainAndDispatch()` runs on the EL
     * and enqueues T_last (the unflushed Nth frame) via `ioDispatcher.dispatch()`. Since the
     * call is made from the EL itself, Netty's `execute()` appends T_last after the current
     * running task. Then `terminate()` is reached; with the pre-fix `withContext(ioDispatcher)`
     * Netty's `isDispatchNeeded()=false` causes the block to run **inline**, sending the
     * `HttpBodyEnd` terminator immediately — before T_last. The client receives N-1 frames and
     * a well-formed terminator; T_last arrives after the encoder has already moved to `NONE` mode
     * and is dropped.
     *
     * **Post-fix (Green)**: `terminate()` uses
     * `ioDispatcher.dispatch(EmptyCoroutineContext) { writeTerminator() }` which always
     * calls `eventLoop.execute()`, enqueuing the terminator task after T_last. The Nth frame
     * is delivered before the terminator, and the client receives all N frames.
     *
     * Red-Green verification: replace [dispatch] with `withContext(ioDispatcher)` in
     * [AbstractPipelinedWriteChannel.terminate] and confirm this test fails on Netty
     * (received = frameCount - 1). Restore [dispatch] and confirm it passes (received = frameCount).
     */
    @Test
    fun `SSE FIFO ordering — Netty SSE all frames arrive before chunked terminator`() {
        val frameCount = 5
        withKeelNettyServer({
            routing {
                get("/sse") {
                    call.respondBytesWriter {
                        repeat(frameCount - 1) { i ->
                            writeStringUtf8("data: event-$i\n\n")
                            flush()
                        }
                        // Final frame is NOT flushed explicitly. Its bytes remain in the internal
                        // buffer until flushAndClose() drains them from the EL thread. With the
                        // pre-fix withContext, writeTerminator() runs inline before the final emit
                        // task, causing the client to receive frameCount-1 frames. With the fixed
                        // dispatch, the emit task is enqueued first, preserving FIFO order.
                        writeStringUtf8("data: event-${frameCount - 1}\n\n")
                    }
                }
            }
        }) { port ->
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = STREAM_TIMEOUT_MS
                socket.getOutputStream().let { out ->
                    out.write("GET /sse HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                }
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                readHttpHeaders(reader)

                var received = 0
                while (true) {
                    val chunk = readNextChunk(reader) ?: break
                    assertEquals("data: event-$received\n\n", chunk, "FIFO ordering: frame $received mismatch")
                    received++
                }
                assertEquals(
                    frameCount,
                    received,
                    "FIFO ordering: expected $frameCount frames but got $received — " +
                        "terminate() placed writeTerminator before the final buffered emit task (FIFO violation)",
                )
            }
        }
    }

    /**
     * Slow-reader high-water audit follow-up: [AbstractPipelinedWriteChannel.flush] must suspend on
     * [PipelinedChannel.awaitFlushComplete] when [PipelinedChannel.isWritable] is `false`,
     * preventing unbounded `pendingWrites` growth when the producer outruns the consumer.
     *
     * **Failure scenario (Red — pre-fix)**: Without the high-water gate, every `flush()`
     * dispatches an `emit` task to the EventLoop and returns immediately. A producer that
     * pumps `N` flush()es back-to-back fills the application-layer `pendingWrites` queue
     * unboundedly while the EL is unable to drain — kernel `sndbuf` fills, then the client
     * `rcvbuf` fills (with the client deliberately not reading), then `pendingWrites`
     * accumulates the rest. The producer lambda completes in ~milliseconds regardless of
     * how slow the client is. Each pending [io.github.fukusaka.keel.buf.IoBuf] holds
     * allocator memory, so a single Slowloris-style slow reader can drive the server's
     * memory footprint up by `total_response_size` per connection.
     *
     * **Post-fix (Green)**: After each `drainAndDispatch`, [flush] suspends on
     * `awaitFlushComplete()` if `pendingBytes >= DEFAULT_HIGH_WATER_MARK` (64 KB). The
     * producer cannot run ahead of the EL; throughput is paced by the slowest consumer.
     *
     * **Observable invariant**: with a client that reads the headers and then pauses
     * without reading any body, `respondBytesWriter` must NOT complete its lambda within
     * the pause window. Pre-fix the lambda returns in ~ms; post-fix it stays suspended
     * until the client resumes.
     *
     * Red-Green verification (manual): comment out the `if (!pipelinedChannel.isWritable)
     * { pipelinedChannel.awaitFlushComplete() }` block in [AbstractPipelinedWriteChannel.flush]
     * and run this test — it must fail (`writerCompleted` becomes `true` during the pause).
     * Restore the gate and the test passes.
     */
    @Test
    fun `slow-reader high-water audit — flush suspends slow-reader producer beyond high-water mark`() {
        slowReaderBackpressureScenario(::withKeelServer, label = "NioEngine")
    }

    @Test
    fun `slow-reader high-water audit — flush suspends slow-reader producer beyond high-water mark on Netty`() {
        slowReaderBackpressureScenario(::withKeelNettyServer, label = "NettyEngine")
    }

    private fun slowReaderBackpressureScenario(
        serverRunner: (suspend Application.() -> Unit, Boolean, (Int) -> Unit) -> Unit,
        label: String,
    ) {
        val writerStarted = CompletableDeferred<Unit>()
        val iterationsCompleted = java.util.concurrent.atomic.AtomicInteger(0)
        // Total bytes must exceed the combined bound of:
        //   - Ktor BufferedByteWriteChannel internal buffer (small, ~4 KB)
        //   - keel pendingWrites high-water mark (DEFAULT_HIGH_WATER_MARK = 64 KB)
        //   - kernel SO_SNDBUF (~256 KB on macOS, may be larger on Linux)
        //   - kernel SO_RCVBUF on the client side (~256 KB)
        // Otherwise everything sits in OS buffers and the gate never fires.
        //
        // The per-iteration `delay()` simulates realistic per-frame application
        // work (event generation, JSON encoding, etc.) and gives the EL time to
        // run the dispatched `emit` task and latch [backpressureSignal] before
        // the producer's next flush. Without this, a tight `repeat(N)` loop on
        // `Dispatchers.IO` races ahead of the EL — the producer dispatches all
        // N emit tasks before the first one runs, never observing the signal.
        // Real SSE / chunked producers naturally pause between frames; this
        // test approximates that.
        val chunkSize = 16 * 1024
        val chunkCount = 500 // = 8 MB total — overflows Linux loopback's auto-tuned rcvbuf max
        val module: suspend Application.() -> Unit = {
            routing {
                get("/slow-pump") {
                    writerStarted.complete(Unit)
                    call.respondBytesWriter {
                        repeat(chunkCount) { i ->
                            writeFully(ByteArray(chunkSize))
                            flush()
                            iterationsCompleted.set(i + 1)
                            delay(SIMULATED_FRAME_GAP_MS)
                        }
                    }
                }
            }
        }

        serverRunner(module, /* keepAlive = */ false) { port ->
            Socket().use { socket ->
                // Pin the client receive buffer small so the test does not depend on
                // platform SO_RCVBUF defaults. Linux auto-tunes rcvbuf into the multi-MB
                // range on loopback, which can absorb the entire test payload before the
                // server's pendingWrites cross DEFAULT_HIGH_WATER_MARK; macOS defaults
                // are ~256 KB which is already enough to need this clamp.
                socket.receiveBufferSize = SLOW_READER_RCVBUF
                socket.soTimeout = STREAM_TIMEOUT_MS
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), STREAM_TIMEOUT_MS)
                socket.getOutputStream().let { out ->
                    out.write("GET /slow-pump HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                }
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                readHttpHeaders(reader)

                // The handler reaches respondBytesWriter; the producer is now pumping.
                runBlocking { withTimeout(5.seconds) { writerStarted.await() } }

                // Pause without reading any body bytes. With the gate the producer suspends
                // before completing all chunks (after pendingBytes crosses high-water);
                // without the gate it completes all chunkCount iterations within ~chunkCount
                // × frame-gap ms.
                Thread.sleep(SLOW_READER_PAUSE_MS)

                val completed = iterationsCompleted.get()
                assertTrue(
                    completed < chunkCount,
                    "$label: producer completed all $chunkCount iterations (= " +
                        "${chunkCount * chunkSize / 1024} KB) during the slow-reader pause. " +
                        "High-water backpressure gate not engaging. Slowloris-style slow " +
                        "readers can drive unbounded pendingWrites memory growth.",
                )

                // Drain the response so the server-side connection close can complete cleanly.
                socket.getInputStream().readBytes()
            }
        }
    }

    // --- Helpers (duplicated from KeelEngineTest to keep this test file self-contained) ---

    private fun withKeelServer(
        module: suspend Application.() -> Unit,
        keepAlive: Boolean = true,
        block: (port: Int) -> Unit,
    ) {
        // Loopback, not the default wildcard: SO_REUSEADDR lets another process
        // bind 127.0.0.1 on the same port after this server is already listening
        // on the wildcard, and a connect to 127.0.0.1 then reaches that later,
        // more specific listener instead of this server. Binding loopback makes
        // the second bind fail with EADDRINUSE, so the port cannot be taken over.
        val server = embeddedServer(Keel, host = "127.0.0.1", port = 0, module = module)
        val cfg = server.engine.configuration
        cfg.engine = NioEngine()
        cfg.keepAlive = keepAlive
        server.start(wait = false)
        try {
            val port = runBlocking {
                withTimeout(15.seconds) {
                    server.engine.resolvedConnectors().first().port
                }
            }
            block(port)
        } finally {
            server.stop(500, 1000)
        }
    }

    private fun withKeelNettyServer(
        module: suspend Application.() -> Unit,
        keepAlive: Boolean = true,
        block: (port: Int) -> Unit,
    ) {
        val server = embeddedServer(Keel, host = "127.0.0.1", port = 0, module = module)
        val cfg = server.engine.configuration
        cfg.engine = NettyEngine()
        cfg.keepAlive = keepAlive
        server.start(wait = false)
        try {
            val port = runBlocking {
                withTimeout(15.seconds) {
                    server.engine.resolvedConnectors().first().port
                }
            }
            block(port)
        } finally {
            server.stop(500, 1000)
        }
    }

    private fun httpGet(port: Int, path: String) {
        val url = URI("http://127.0.0.1:$port$path").toURL()
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.requestMethod = "GET"
            // Drain whatever the server emits before it cancels.
            conn.responseCode
            conn.inputStream.use { stream ->
                runCatching { stream.readBytes() }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun readHttpHeaders(reader: BufferedReader): Map<String, String> {
        // Check the status line rather than skip it. Reading past a non-200
        // reply pushes its body into the chunk parser, which then fails on
        // whatever text it finds instead of naming the response that arrived.
        val statusLine = reader.readLine() ?: error("EOF before status line")
        check(statusLine.startsWith("HTTP/1.1 200") || statusLine.startsWith("HTTP/1.0 200")) {
            "expected a 200 response, got \"$statusLine\""
        }
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers.putIfAbsent(line.substring(0, colon).trim(), line.substring(colon + 1).trim())
            }
        }
        return headers
    }

    private fun readNextChunk(reader: BufferedReader): String? {
        val sizeLine = reader.readLine() ?: return null
        // Report what was actually on the wire. A bare NumberFormatException
        // names the offending token and nothing else, which leaves a one-off
        // failure undiagnosable: there is no way to tell a mis-framed keel
        // response from a reply that never came from keel at all.
        val size = sizeLine.trim().toIntOrNull(HEX_RADIX) ?: error(
            "expected a hex chunk size but read \"$sizeLine\"; remainder of the stream: " +
                generateSequence { reader.readLine() }.take(REMAINDER_DUMP_LINES).joinToString("\\n"),
        )
        if (size == 0) {
            reader.readLine()
            return null
        }
        val buf = CharArray(size)
        var pos = 0
        while (pos < size) {
            val n = reader.read(buf, pos, size - pos)
            if (n == -1) error("Unexpected EOF reading chunk data at offset $pos of $size")
            pos += n
        }
        reader.readLine()
        return String(buf, 0, pos)
    }

    private companion object {
        private const val STREAM_TIMEOUT_MS = 5_000
        private const val HEX_RADIX = 16

        /** Lines of the unparsed response to quote when chunk framing does not hold. */
        private const val REMAINDER_DUMP_LINES = 20

        /**
         * Pause window for `slow-reader high-water audit — flush suspends slow-reader producer` tests.
         * Must be long enough that a Red (gate-disabled) producer would have completed
         * its full N × chunkSize dispatch loop, but short enough to keep the test fast.
         * 1 second comfortably exceeds the few-millisecond Red-state runtime.
         */
        private const val SLOW_READER_PAUSE_MS = 3_000L

        /**
         * Per-frame gap inside the test producer. Approximates the natural
         * pacing of realistic SSE / chunked producers (event generation, JSON
         * encoding, etc.) and gives the EL thread time to drain dispatched
         * `emit` tasks so the backpressure signal can latch ahead of the next
         * `flush()`.
         */
        private const val SIMULATED_FRAME_GAP_MS = 5L

        /**
         * Client-side `SO_RCVBUF` for the slow-reader test. Pinned to a small value
         * (16 KB) so the test does not depend on platform-specific receive-buffer
         * auto-tuning — Linux loopback can otherwise grow rcvbuf into the multi-MB
         * range, absorbing the full test payload before any server-side gate fires.
         */
        private const val SLOW_READER_RCVBUF = 16 * 1024
    }
}

/**
 * Regression tests for [KeelByteWriteChannel.emit]'s zero-copy wrap fast path:
 * drains at or above the wrap threshold are forwarded via
 * [io.github.fukusaka.keel.buf.BufferAllocator.wrapBytes] instead of
 * allocate+copy (which fell through to an unpooled exact-size direct-buffer
 * allocation per large response). Covers both failure modes of the change:
 * body corruption (wrapped array delivered wrongly) and buffer leaks
 * (wrapped IoBuf not released along the encoder/transport path).
 */
class KeelByteWriteChannelWrapPathTest {

    @Test
    fun `large and threshold-boundary bodies arrive intact and leak-free through emit`() {
        val allocator = io.github.fukusaka.keel.buf.defaultAllocator().withTracking()
        // 100 KB — realistic /large-sized drain, well above the wrap threshold.
        val big = ByteArray(100_000) { (it % 251).toByte() }
        // One byte below the threshold — pins the pooled copy path.
        val small = ByteArray(8_191) { (it % 13).toByte() }
        val server = embeddedServer(Keel, host = "127.0.0.1", port = 0) {
            routing {
                get("/big") { call.respondBytesWriter { writeFully(big) } }
                get("/small") { call.respondBytesWriter { writeFully(small) } }
            }
        }
        val cfg = server.engine.configuration
        cfg.engine = NioEngine(io.github.fukusaka.keel.core.IoEngineConfig(allocator = allocator))
        server.start(wait = false)
        try {
            val port = runBlocking {
                withTimeout(15.seconds) { server.engine.resolvedConnectors().first().port }
            }
            kotlin.test.assertContentEquals(big, httpGetBytes(port, "/big"))
            kotlin.test.assertContentEquals(small, httpGetBytes(port, "/small"))
        } finally {
            server.stop(500, 1000)
        }
        // Releases complete on the EventLoop; poll briefly before asserting.
        runBlocking {
            withTimeout(5.seconds) {
                while (allocator.outstandingCount > 0) delay(10)
            }
        }
        allocator.assertNoLeaks()
    }

    private fun httpGetBytes(port: Int, path: String): ByteArray {
        val url = URI("http://127.0.0.1:$port$path").toURL()
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 15_000
            conn.inputStream.readBytes()
        } finally {
            conn.disconnect()
        }
    }
}
