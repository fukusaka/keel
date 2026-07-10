package io.github.fukusaka.keel.server.ktor.cio

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.io.toDecLongOrNull
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.logging.error
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.server.ktor.KeelApplicationEngine
import io.github.fukusaka.keel.server.ktor.KtorConnectionHandler
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.cio.ConnectionOptions
import io.ktor.http.cio.expectHttpBody
import io.ktor.http.cio.parseHttpBody
import io.ktor.http.cio.parseRequest
import io.ktor.util.pipeline.execute
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.availableForRead
import io.ktor.utils.io.close
import io.ktor.utils.io.discard
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.bytestring.ByteString
import kotlinx.io.indexOf
import kotlin.coroutines.ContinuationInterceptor

/**
 * [KtorConnectionHandler] backed by [ktor-http-cio's][io.ktor.http.cio]
 * `parseRequest` / `parseHttpBody`.
 *
 * **Architecture**:
 *
 * ```
 *                  KtorCioInboundBridge      inputPump
 * keel transport ──────────────────────────────────────► Ktor ByteChannel ────► parseRequest
 *   (pipeline.notifyRead → InboundHandler →                                           │
 *    Channel<IoBuf> → receiveCatching)                                                ▼
 *                                                                            application pipeline
 *                                                                                     │
 *                                                                                     ▼
 *                                                                         KeelCioApplicationResponse
 *                                                                                     │
 *                  outputPump                              Ktor ByteChannel  ◄────────┘
 * keel transport ◄────────────────────
 * ```
 *
 * Inbound: a [KtorCioInboundBridge] handler consumes [IoBuf]
 * from the pipeline directly into a coroutine [kotlinx.coroutines.channels.Channel];
 * the input pump drains it into a Ktor `ByteChannel` for `parseRequest`.
 * This mirrors the [io.github.fukusaka.keel.pipeline.SuspendMessageBridge]
 * shape used by `:keel-codec-http` and `:keel-server-ktor`, shortening
 * close propagation from the prior 4-hop indirect chain
 * (`SuspendBridgeHandler` → `BufferedSuspendSource` → `ByteChannel`) to
 * 2 hops (handler → bridge channel close).
 *
 * The keep-alive loop reads [parseRequest] from the input channel, builds a
 * [KeelCioApplicationCall] with a body sub-channel decoded by [parseHttpBody],
 * dispatches via `engine.pipeline.execute(call)`, then drains any unread body
 * before reading the next request.
 *
 * **Concurrency**:
 *
 * - Inbound pump runs on the channel's `ioDispatcher` so reads never cross-thread
 * - Outbound pump runs on the channel's `ioDispatcher` for the same reason
 * - The keep-alive loop runs on the configured `applicationDispatcher`
 *   (defaults to `ioDispatcher`)
 *
 * Pumps are launched on [scope] so they're children of the engine's
 * SupervisorJob — they get cancelled cleanly when the connection closes.
 *
 * **Native parser serialisation**: every call to ktor-http-cio's
 * `parseRequest` **and the matching `request.release()`** is wrapped in
 * [HeaderParseMutex] to avoid a Kotlin/Native lock contention storm in
 * `HeadersDataPool`.  parseRequest borrows from the pool, release recycles
 * to it; serialising both ends closes the borrow ↔ recycle race.  See
 * [HeaderParseMutex] for evidence and [KeelCio] for the documented
 * trade-off.  `parseHttpBody` is intentionally *not* serialised — body
 * decoding is per-connection (no shared pool contention) and may run for
 * unbounded durations on streaming uploads.
 */
internal class KtorCioConnectionHandler(
    /**
     * Serialises both `parseRequest` (header borrow) and the matching
     * `request.release()` (header recycle) so concurrent header parsing
     * on Kotlin/Native does not pathologically contend on the shared
     * `HeadersDataPool` lock inside ktor-http-cio.  See [HeaderParseMutex]
     * for the empirical evidence and the JVM / Native split (no-op on
     * JVM, process-wide [kotlinx.coroutines.sync.Mutex] on Native).  The
     * mutex is coroutine-level, so suspension does not block the I/O
     * thread.  `parseHttpBody` runs without this serialisation (per-call
     * body decoding does not touch the shared pool).
     *
     * Constructor seam — defaults to a fresh `HeaderParseMutex()` for
     * production. Tests override with a recording subclass to assert
     * that both borrow and recycle paths route through the mutex (see
     * `KtorCioRequestReleaseSerialisationTest`).
     */
    private val parserMutex: HeaderParseMutex = HeaderParseMutex(),
) : KtorConnectionHandler {

    override suspend fun handle(
        channel: PipelinedChannel,
        scheme: String,
        engine: KeelApplicationEngine,
        scope: CoroutineScope,
    ) {
        // Install the inbound bridge as the terminal handler before arming
        // the read loop.  Both must happen on the EventLoop thread so the
        // first onRead callback sees the bridge installed.
        //
        // Allow user-injected handlers via Configuration.pipelineCustomizer
        // — same hook as the Keel codec path. KeelCio's pipeline carries
        // raw byte-level messages (the ktor-http-cio parser operates on
        // ByteChannel directly, not on keel HttpResponseHead/Body), so a
        // CompressionHandler installed here would NOT intercept response
        // bytes; users wanting compression on KeelCio should use Ktor's
        // application-level Compression plugin (JVM) or the standalone
        // ktor-http-cio compression integration (future). The hook still
        // fires here for symmetry with the Keel path so byte-level
        // handlers (tracing, metrics, byte-counters) work uniformly.
        val bridge = KtorCioInboundBridge()
        withContext(channel.ioDispatcher) {
            engine.configuration.pipelineCustomizer?.invoke(channel)
            channel.pipeline.addLast(INBOUND_BRIDGE_NAME, bridge)
            channel.readEnabled = true
        }

        // Two ByteChannels bridge keel ↔ Ktor.  autoFlush=true so individual
        // writeFully / writeStringUtf8 calls become observable to the reader
        // immediately (parseRequest, response writer).
        val input = ByteChannel(autoFlush = true)
        val output = ByteChannel(autoFlush = true)

        val inputPump = scope.launch(channel.ioDispatcher) {
            pumpBridgeToInput(bridge, input, engine.logger)
        }
        val outputPump = scope.launch(channel.ioDispatcher) {
            pumpOutputToChannel(output, channel, engine.logger)
        }

        try {
            keepAliveLoop(channel, scheme, engine, scope, input, output)
        } catch (e: Exception) {
            if (e !is CancellationException) {
                engine.logger.error(e) { "ktor-cio connection failed" }
            }
            if (e is CancellationException) throw e
        } finally {
            // Drain output then close it so the outputPump exits cleanly.
            runCatching { output.flushAndClose() }
            // Cancel the input pump if still running — keep-alive may have
            // exited mid-request (e.g. on connection close from the peer).
            inputPump.cancel()
            outputPump.join()
            // Wait for the transport to drain any writes that stalled on EAGAIN
            // before pumpOutputToChannel exited. Without this, teardownOnEventLoop
            // releases pendingWrites before EPOLLOUT fires, sending a partial
            // response and leaving the peer (e.g. k6) stalled on the missing
            // chunked terminator.
            runCatching { channel.awaitFlushComplete() }
            // Release any IoBufs left in the bridge queue before the
            // pipeline tears down.
            bridge.close()
            runCatching { channel.close() }
        }
    }

    @Suppress("LongParameterList")
    private suspend fun keepAliveLoop(
        channel: PipelinedChannel,
        scheme: String,
        engine: KeelApplicationEngine,
        scope: CoroutineScope,
        input: ByteReadChannel,
        output: ByteWriteChannel,
    ) {
        val configuration = engine.configuration
        val serverKeepAlive = configuration.keepAlive

        while (channel.isActive && !input.isClosedForRead) {
            // Buffer the whole request head BEFORE taking the parse mutex, so
            // `parseRequest` consumes already-arrived bytes and never suspends
            // while holding it. On Native the mutex is process-wide, so any
            // suspension under it stalls every other connection's parse: a
            // pooled client idling between keep-alive requests, a peer that
            // sends only the leading CRLF that RFC 9112 3.5 permits, or one
            // that stops mid-request-line would each park with the lock held
            // until they disconnect.
            if (!input.awaitRequestHead()) break
            val request = parserMutex.withLock { parseRequest(input) } ?: break

            val length = request.headers[HttpHeaders.ContentLength]?.toDecLongOrNull() ?: -1L
            val transferEncoding = request.headers[HttpHeaders.TransferEncoding]
            val connectionOptions = ConnectionOptions.parse(request.headers[HttpHeaders.Connection])
            val expectsBody = expectHttpBody(request)

            val keepAlive = serverKeepAlive && computeKeepAlive(request.version, connectionOptions)

            val bodyChannel: ByteReadChannel = if (expectsBody) {
                val pipe = ByteChannel(autoFlush = true)
                val pumpJob = parseBodyJob(
                    scope = scope,
                    version = request.version,
                    length = length,
                    transferEncoding = transferEncoding,
                    connectionOptions = connectionOptions,
                    input = input,
                    output = pipe,
                )
                pumpJob.invokeOnCompletion { pipe.close() }
                pipe
            } else {
                ByteReadChannel.Empty
            }

            val call = KeelCioApplicationCall(
                application = engine.application(),
                cioRequest = request,
                requestBody = bodyChannel,
                rawInput = input,
                output = output,
                pipelinedChannel = channel,
                localAddress = channel.localAddress,
                remoteAddress = channel.remoteAddress,
                scope = scope,
                coroutineContext = scope.coroutineContext,
                keepAlive = keepAlive,
                scheme = scheme,
            )

            try {
                val appCtx = configuration.applicationDispatcher ?: channel.ioDispatcher
                if (appCtx !== scope.coroutineContext[ContinuationInterceptor]) {
                    withContext(appCtx) { engine.pipeline.execute(call) }
                } else {
                    engine.pipeline.execute(call)
                }
            } finally {
                // Serialise request.release() through parserMutex too — not just
                // parseRequest. release() walks Request.headers.release() which
                // calls HeadersDataPool.recycle(), and recycle() acquires the
                // SAME pool lock that parseRequest's borrow() takes. Without
                // serialisation, worker A's release runs concurrently with
                // worker B's parseRequest, and B's borrow holds the
                // HeadersDataPool lock while clearInstance() reaches into
                // IntArrayPool.recycle() (a different pool, hence a nested lock
                // acquisition). Under multi-worker bursts (e.g. fresh Native
                // instance taking 50 keep-alive connections at once on a
                // deployment), the cascading lock waits on Kotlin/Native's
                // SynchronizedObject (escalating to pthread_mutex on
                // contention) collapse parser throughput in the same way
                // HeaderParseMutex was originally introduced to prevent. Cover
                // the recycle path here so the borrow ↔ recycle race is closed.
                parserMutex.withLock { request.release() }
                runCatching { bodyChannel.discard() }
                output.flush()
            }

            // Cancel-without-rethrow: if the streaming write channel was terminated via cancel() (e.g. a
            // client disconnection during SSE, or application code that calls cancel()
            // without rethrowing), the chunked terminator `0\r\n\r\n` (or the full
            // Content-Length body) was never written. Advancing to the next keep-alive
            // request would write the next response's headers before the terminator,
            // desynchronising the client's HTTP parser. Close the connection instead.
            if (call.response.writeChannelCancelled) break
            // A protocol upgrade (e.g. WebSocket) was performed: the upgrade session
            // now owns the raw input/output channels. Join the session job to keep
            // the connection alive until the peer closes, then exit the keep-alive loop.
            val upgradeJob = call.response.upgradeJob
            if (upgradeJob != null) {
                upgradeJob.join()
                break
            }

            if (!keepAlive) break
        }
    }

    private fun parseBodyJob(
        scope: CoroutineScope,
        version: CharSequence,
        length: Long,
        transferEncoding: CharSequence?,
        connectionOptions: ConnectionOptions?,
        input: ByteReadChannel,
        output: ByteWriteChannel,
    ): Job = scope.launch {
        try {
            parseHttpBody(
                HttpProtocolVersion.parse(version),
                length,
                transferEncoding,
                connectionOptions,
                input,
                output,
            )
        } catch (e: Exception) {
            output.cancel(e)
            if (e is CancellationException) throw e
        }
    }

    /**
     * Computes whether the connection should remain open after this response.
     * RFC 7230 §6.3: HTTP/1.1 defaults to keep-alive unless `Connection: close`;
     * HTTP/1.0 defaults to close unless `Connection: keep-alive`.
     */
    private fun computeKeepAlive(version: CharSequence, options: ConnectionOptions?): Boolean {
        if (options?.close == true) return false
        return when {
            version.contentEquals("HTTP/1.1", ignoreCase = true) -> true
            version.contentEquals("HTTP/1.0", ignoreCase = true) -> options?.keepAlive == true
            else -> false
        }
    }

    /**
     * Forwards [IoBuf]s arriving on [bridge]
     * directly into [output] (a Ktor `ByteChannel`).  Each buffer is copied
     * into a reusable [ByteArray] and released back to the engine before
     * the next receive — there is no internal buffering on top of the
     * bridge channel.
     *
     * Close propagation: bridge closure (peer EOF, pipeline `onError`, or
     * explicit [KtorCioInboundBridge.close]) returns a closed
     * [ChannelResult]; we forward the cause (if any) to [output] via
     * `cancel`, otherwise close cleanly so `parseRequest` observes EOF.
     */
    private suspend fun pumpBridgeToInput(
        bridge: KtorCioInboundBridge,
        output: ByteWriteChannel,
        logger: Logger,
    ) {
        val tmp = ByteArray(PUMP_BUFFER_SIZE)
        try {
            while (!output.isClosedForWrite) {
                val received: ChannelResult<IoBuf> = bridge.receiveCatching()
                if (received.isClosed) {
                    val cause = received.exceptionOrNull()
                    if (cause != null && cause !is CancellationException) {
                        output.cancel(cause)
                        return
                    }
                    break
                }
                val buf = received.getOrThrow()
                try {
                    while (buf.readableBytes > 0) {
                        val n = minOf(buf.readableBytes, tmp.size)
                        buf.readByteArray(tmp, 0, n)
                        output.writeFully(tmp, 0, n)
                    }
                } finally {
                    buf.release()
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.debug { "ktor-cio inbound pump terminated by I/O error: ${e::class.simpleName}: ${e.message}" }
            output.cancel(e)
        } finally {
            output.flushAndClose()
        }
    }

    /**
     * Reads bytes from [input] (the response writer's side) and forwards them
     * to the keel transport via the channel's pipeline (no codec installed —
     * bytes flow straight through to the underlying socket).
     *
     * **Backpressure**: after each [requestFlush][PipelinedChannel.pipeline], if
     * [channel.isWritable][PipelinedChannel.isWritable] is false (pending bytes exceed
     * the high-water mark), [awaitFlushComplete][PipelinedChannel.awaitFlushComplete]
     * is called so the pump suspends. This yields the EventLoop thread and lets the
     * transport drain `pendingWrites` via the write-readiness callback before
     * accumulating more data. Without this gate, a large response body fills the socket
     * send buffer, every `write(2)` returns `EAGAIN`, and the tight read-ahead loop
     * never yields — the EventLoop thread never reaches `kevent(2)` / `epoll_wait(2)`,
     * so write-readiness events are never processed and throughput collapses. Mirrors
     * the slow-reader high-water fix on the upgrade pump (see `KeelApplicationResponse.pumpOutputToRaw`).
     */
    private suspend fun pumpOutputToChannel(
        input: ByteReadChannel,
        channel: PipelinedChannel,
        logger: Logger,
    ) {
        val buf = ByteArray(PUMP_BUFFER_SIZE)
        try {
            while (!input.isClosedForRead) {
                val n = input.readAvailable(buf)
                if (n == -1) break
                if (n > 0) {
                    val ioBuf = channel.allocator.allocate(n)
                    ioBuf.writeByteArray(buf, 0, n)
                    channel.pipeline.requestWrite(ioBuf)
                    channel.pipeline.requestFlush()
                    if (!channel.isWritable) {
                        channel.awaitFlushComplete()
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Connection write failure usually means the peer already closed.
            // Log at DEBUG so operators can diagnose unexpected disconnects
            // without making routine peer-close noise visible at INFO+.
            // Rethrowing would mask the original cause from the keep-alive loop.
            logger.debug { "ktor-cio outbound pump terminated by I/O error: ${e::class.simpleName}: ${e.message}" }
        }
    }

    private companion object {
        private const val PUMP_BUFFER_SIZE = 8192
        private const val INBOUND_BRIDGE_NAME = "__ktor_cio_inbound__"
    }
}

/**
 * Suspends until a complete request head — everything up to and including the
 * terminating `CRLF CRLF` — is buffered in [this], or the peer closes.
 *
 * `parseRequest` must run under the process-wide parse mutex on Native (see
 * [HeaderParseMutex]), and anything it suspends on suspends every other
 * connection's parse with it. Buffering the head first means the locked
 * `parseRequest` only consumes bytes that already arrived.
 *
 * Leading empty lines belong to the head: RFC 9112 3.5 lets a client send
 * `CRLF`s before the request line and `parseRequest` skips them, so a head is
 * complete only once a `CRLF CRLF` has arrived. `indexOf` scans the channel's
 * already-buffered bytes without consuming them, and `awaitContent(min)` both
 * waits for the next byte and folds the channel's flush buffer into the buffer
 * that scan reads.
 *
 * @return `true` when a full head is buffered, `false` on EOF.
 * @throws IOException when the head reaches [MAX_REQUEST_HEAD_BYTES] without a
 *   terminator — a peer that never finishes its head would otherwise buffer
 *   without bound.
 */
@OptIn(InternalAPI::class)
private suspend fun ByteReadChannel.awaitRequestHead(): Boolean {
    while (true) {
        if (readBuffer.indexOf(HEAD_TERMINATOR) >= 0) return true
        val buffered = availableForRead
        if (buffered >= MAX_REQUEST_HEAD_BYTES) {
            throw IOException("request head reached $MAX_REQUEST_HEAD_BYTES bytes with no CRLF CRLF terminator")
        }
        if (!awaitContent(min = buffered + 1)) return false
    }
}

/** `CRLF CRLF` — the end of an HTTP/1.x request head. */
private val HEAD_TERMINATOR = ByteString(CR, LF, CR, LF)

private const val CR: Byte = 0x0D
private const val LF: Byte = 0x0A

/**
 * Upper bound on a buffered request head. ktor-http-cio caps a single line at
 * its own `HTTP_LINE_LIMIT` but not the head as a whole; this bounds the memory
 * one peer can pin before its head is rejected.
 */
private const val MAX_REQUEST_HEAD_BYTES = 64 * 1024
