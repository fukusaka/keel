package io.github.fukusaka.keel.server.ktor.cio

import io.github.fukusaka.keel.buf.IoBuf
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
 * `parseRequest` is wrapped in [HeaderParseMutex] to avoid a
 * Kotlin/Native lock contention storm in `HeadersDataPool` when many
 * concurrent connections parse headers simultaneously.  See
 * [HeaderParseMutex] for evidence and [KeelCio] for the documented
 * trade-off.  `parseHttpBody` is intentionally *not* serialised — body
 * decoding is per-connection (no shared pool contention) and may run for
 * unbounded durations on streaming uploads.
 */
internal class KtorCioConnectionHandler : KtorConnectionHandler {

    /**
     * Serialises every `parseRequest` / `parseHttpBody` call so concurrent
     * header parsing on Kotlin/Native does not pathologically contend on
     * the shared `HeadersDataPool` lock inside ktor-http-cio.  See
     * [HeaderParseMutex] for the empirical evidence and the JVM /
     * Native split (no-op on JVM, process-wide [kotlinx.coroutines.sync.Mutex]
     * on Native).  The mutex is coroutine-level, so suspension does not
     * block the I/O thread.
     */
    private val parserMutex = HeaderParseMutex()

    override suspend fun handle(
        channel: PipelinedChannel,
        scheme: String,
        engine: KeelApplicationEngine,
        scope: CoroutineScope,
    ) {
        // Install the inbound bridge as the terminal handler before arming
        // the read loop.  Both must happen on the EventLoop thread so the
        // first onRead callback sees the bridge installed.
        val bridge = KtorCioInboundBridge()
        withContext(channel.ioDispatcher) {
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
            val request = parserMutex.withLock { parseRequest(input) } ?: break

            val length = request.headers[HttpHeaders.ContentLength]?.parseDecLong() ?: -1L
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
                request.release()
                runCatching { bodyChannel.discard() }
                output.flush()
            }

            // A protocol upgrade (e.g. WebSocket) was performed: the upgrade session
            // now owns the raw input/output channels. Join the session job to keep
            // the connection alive until the peer closes, then exit the keep-alive loop.
            //
            // K23: bound the wait to [WS_CLOSE_TIMEOUT_MS]. Ktor's WebSocketSession
            // can deadlock with our pipeline-side teardown waiting for `inputChannel`
            // EOF that is itself blocked behind `upgradeJob` completion (observed on
            // ktor-cio-keel-nio macOS). Bounding the join lets the connection tear
            // down within the timeout instead of pinning a connection slot
            // indefinitely; the proper fix needs cross-engine teardown ordering.
            val upgradeJob = call.response.upgradeJob
            if (upgradeJob != null) {
                kotlinx.coroutines.withTimeoutOrNull(WS_CLOSE_TIMEOUT_MS) {
                    upgradeJob.join()
                }
                if (upgradeJob.isActive) upgradeJob.cancel()
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

    private fun CharSequence.parseDecLong(): Long? {
        var result = 0L
        for (i in 0 until length) {
            val c = this[i]
            if (c !in '0'..'9') return null
            result = result * BASE_TEN + (c - '0')
        }
        return result
    }

    private companion object {
        private const val PUMP_BUFFER_SIZE = 8192
        private const val BASE_TEN = 10
        private const val INBOUND_BRIDGE_NAME = "__ktor_cio_inbound__"

        /** Upper bound (ms) on how long the connection handler waits for
         *  Ktor's WebSocket `upgradeJob` to complete before forcing
         *  teardown. See K23 in status.md — Ktor's WebSocketSession can
         *  deadlock with our pipeline-side teardown waiting for input
         *  EOF that is itself blocked behind `upgradeJob` completion.
         *  5 s is generous compared to typical close handshake latency
         *  and tight enough that a stuck upgrade does not pin a
         *  connection slot indefinitely. */
        private const val WS_CLOSE_TIMEOUT_MS = 5_000L
    }
}
