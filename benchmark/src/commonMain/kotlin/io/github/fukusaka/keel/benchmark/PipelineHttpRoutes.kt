package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.codec.http.HttpBody
import io.github.fukusaka.keel.codec.http.HttpBodyEnd
import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpRequestDecoder
import io.github.fukusaka.keel.codec.http.HttpRequestHead
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseEncoder
import io.github.fukusaka.keel.codec.http.HttpResponseHead
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.codec.http.HttpVersion
import io.github.fukusaka.keel.codec.websocket.WsFrame
import io.github.fukusaka.keel.codec.websocket.WsFrameDecoder
import io.github.fukusaka.keel.codec.websocket.WsFrameEncoder
import io.github.fukusaka.keel.codec.websocket.WsOpcode
import io.github.fukusaka.keel.codec.websocket.computeAcceptKey
import io.github.fukusaka.keel.codec.websocket.validateClientKey
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.Pipeline
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext

/**
 * Pre-built responses shared across all pipeline-http benchmark servers.
 *
 * Header flat-array caches are warmed at construction time so that the
 * first request on each EventLoop thread does not pay the lazy-init cost.
 */
object PipelineHttpResponses {
    val hello: HttpResponse = HttpResponse.ok("Hello, World!", contentType = "text/plain").also { it.headers.size }
    val large: HttpResponse = HttpResponse.ok("x".repeat(LARGE_PAYLOAD_SIZE), contentType = "text/plain").also { it.headers.size }
}

/**
 * Installs the standard pipeline-http handler stack into [pipeline]:
 *
 * ```
 * HEAD ↔ [tls] ↔ encoder ↔ decoder ↔ routing ↔ TAIL
 * ```
 *
 * No [HttpBodyAggregator] — the routing handler consumes streaming
 * body messages ([HttpBody] / [HttpBodyEnd]) directly. This avoids
 * the aggregator's per-request body copy overhead in benchmarks.
 */
fun installPipelineHttpHandlers(pipeline: Pipeline, compression: Boolean = false) {
    pipeline.addLast("encoder", HttpResponseEncoder())
    pipeline.addLast("decoder", HttpRequestDecoder())
    if (compression) {
        // Both directions of compression share one per-channel registry
        // (`gzip` + `deflate` from `keel-compression-zlib`):
        //
        // - **Outbound** [io.github.fukusaka.keel.codec.http.CompressionHandler]:
        //   intercepts routing's HttpResponseHead → HttpBody* → HttpBodyEnd
        //   before the encoder. Pipeline addLast appends to tail; outbound
        //   flows tail → head, so insertion order here is
        //     encoder (tail) ← decoder ← compression ← request-decompression ← routing (head)
        //   and routing's response traverses request-decompression (passthrough
        //   for outbound) → compression → encoder.
        // - **Inbound** [io.github.fukusaka.keel.codec.http.HttpRequestDecompressionHandler]:
        //   intercepts decoder's HttpRequestHead → HttpBody* → HttpBodyEnd
        //   before the routing handler, applying the secure-by-default
        //   dual-gate zip-bomb defence (1 MiB absolute / 100:1 ratio /
        //   burst 3, registry-driven encoding lookup).
        val registry = io.github.fukusaka.keel.compression.CompressionRegistry().apply {
            register(io.github.fukusaka.keel.compression.zlib.GzipCodec)
            register(io.github.fukusaka.keel.compression.zlib.DeflateCodec)
        }
        pipeline.addLast(
            "compression",
            io.github.fukusaka.keel.codec.http.CompressionHandler(
                registry = registry,
                allocator = io.github.fukusaka.keel.buf.DefaultAllocator,
            ),
        )
        pipeline.addLast(
            "request-decompression",
            io.github.fukusaka.keel.codec.http.HttpRequestDecompressionHandler(
                registry = registry,
                allocator = io.github.fukusaka.keel.buf.DefaultAllocator,
            ),
        )
    }
    pipeline.addLast("routing", BenchmarkRoutingHandler())
}

/**
 * Terminal inbound handler for pipeline-http benchmarks.
 *
 * Handles the streaming HTTP message protocol directly:
 * [HttpRequestHead] → [HttpBody] × N → [HttpBodyEnd].
 *
 * Routes:
 * - `/hello` — 13-byte "Hello, World!" (pre-built, zero per-request allocation)
 * - `/large` — 100 KB payload (pre-built)
 * - `/echo`  — accumulates request body chunks and echoes them back
 * - `/upload-stream` — discards request body chunks, replies 200 + size header
 *   (request-body streaming throughput / heap-impact bench)
 * - `/multipart-upload` — streams the request body, scans each chunk for the
 *   bench scenario's known boundary marker (`--KeelBenchBoundaryV1`), counts
 *   the parts, replies 200 + count + size headers. No framework multipart
 *   parser; the boundary scan is the cheapest possible "parse" so the
 *   bench number is the wire-side baseline against which framework parsers
 *   (Spring / Vertx / Netty / Ktor) can be compared in `bench-stream-one.sh
 *   multipart`.
 * - `/sse-stream?count=N&size=M` — emits N chunks of M bytes via chunked
 *   Transfer-Encoding (response-body streaming throughput bench)
 * - `/ws-echo` — WebSocket (RFC 6455) echo server; swaps the pipeline codec
 *   from HTTP to WS frames after the handshake and echoes each received frame
 *   back (masking stripped per RFC 6455 §5.3).
 * - `/ws-deflate` — WebSocket echo server with bench-only frame-level
 *   `permessage-deflate` (RFC 7692). When the client offers the extension,
 *   the 101 response advertises it, the WS decoder is installed with
 *   `allowRsv1 = true`, and each inbound RSV1=1 frame is genuinely
 *   decompressed → recompressed before being echoed (see
 *   [PipelineHttpWsDeflate]). This is the `pipeline-http-*` counterpart of
 *   the `server-http-*` `webSockets(DeflateCodec)` path.
 * - others   — 404 Not Found
 *
 * Instantiated per-connection because [currentPath] / [echoStreaming] /
 * [uploadBytes] are mutable state scoped to the current request.
 */
private class BenchmarkRoutingHandler : InboundHandler {

    private var currentPath: String? = null
    private var echoStreaming: Boolean = false
    private var uploadStreaming: Boolean = false
    private var uploadBytes: Long = 0L
    private var multipartStreaming: Boolean = false
    private var multipartParts: Int = 0
    // 1-byte carry buffer for boundary scanning across chunk boundaries.
    // Boundary length is constant (BENCHMARK_MULTIPART_BOUNDARY.size) and
    // small (<60 bytes), so we keep the last (boundaryLen - 1) bytes from
    // the previous chunk to catch boundary occurrences that span chunks.
    private var multipartCarry: ByteArray = EMPTY_BYTE_ARRAY
    private var sseStreaming: Boolean = false
    private var methodEchoMethod: String? = null
    private var itemEchoId: String? = null

    // WebSocket state — active only after a successful /ws-echo or
    // /ws-deflate upgrade.
    private var wsUpgradePending: Boolean = false
    private var wsClientKey: String? = null
    private var wsEchoMode: Boolean = false

    // --- Backpressure state ---
    //
    // The pipeline-http routes form a *user-facing sample* of the sync-handler
    // backpressure pattern. The async server-http path (see #784 / #785) is
    // gated via suspending `awaitFlushComplete` inside the handler; sync
    // pipeline handlers cannot suspend, so the engine surfaces a callback
    // (`PipelineHandler.onWritabilityChanged`) and exposes two flow-control
    // primitives:
    //
    // - `ctx.channel.isWritable` — `false` once buffered outbound bytes cross
    //   the transport's high watermark; producers must stop emitting.
    // - `ctx.channel.pauseReads()` / `resumeReads()` — flip the engine's
    //   socket-drain off / on so kernel `rcvbuf` fills and TCP flow control
    //   reaches the peer.
    //
    // Two paths use them:
    //
    // 1. *Echo* (HttpBody → propagateWrite → encoder). Each `propagateWrite`
    //    grows the transport's pending-bytes; once `isWritable` goes false
    //    we `pauseReads()` so the peer stops sending. `onWritabilityChanged`
    //    re-arms reads when the drain catches up.
    // 2. *SSE* (`/sse-stream?count=N`). The emission loop becomes a small
    //    state machine: `pumpSseStream` emits frames while `isWritable`, then
    //    parks the remaining count on `pendingSseEmission`. The same
    //    `onWritabilityChanged` callback resumes the pump.
    private var readsPausedByBackpressure: Boolean = false
    private var pendingSseEmission: SseEmissionState? = null

    // permessage-deflate state for the /ws-deflate route. `wsDeflateOffered`
    // records whether the upgrade request offered the extension; the
    // [PipelineHttpWsDeflate] engine is created only once the handshake
    // completes with the offer accepted.
    private var wsUpgradePath: String? = null
    private var wsDeflateOffered: Boolean = false
    private var wsDeflate: PipelineHttpWsDeflate? = null

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        when (msg) {
            is HttpRequestHead -> {
                currentPath = msg.path
                echoStreaming = false
                uploadStreaming = false
                uploadBytes = 0L
                methodEchoMethod = null
                itemEchoId = null
                // WS upgrade: stash the client key and defer the handshake to
                // HttpBodyEnd (the request body is empty for GET/upgrade
                // requests but the decoder still emits HttpBodyEnd to close
                // the message). /ws-deflate additionally records whether the
                // request offered permessage-deflate so the 101 response can
                // accept it.
                if ((msg.path == "/ws-echo" || msg.path == "/ws-deflate") && msg.isWebSocketUpgrade()) {
                    wsUpgradePending = true
                    wsUpgradePath = msg.path
                    wsClientKey = msg.headers.getString("Sec-WebSocket-Key")
                    wsDeflateOffered = msg.path == "/ws-deflate" &&
                        PipelineHttpWsDeflate.offersPermessageDeflate(msg.headers.getString("Sec-WebSocket-Extensions"))
                    // Release the parsed headers' backing recv buffer. With the
                    // PR #596 byte-range storage contract, HttpHeaders retains
                    // the recv IoBuf until release(); on io-uring the provided
                    // buffer ring has only DEFAULT_BUFFER_COUNT slots, so a
                    // missing release here exhausts the ring within ~64 requests
                    // and collapses throughput by ~6000×.
                    msg.headers.release()
                    return
                }
                when {
                    msg.path == "/echo" -> {
                        // Start streaming response immediately with chunked encoding.
                        // Body chunks from the request will be forwarded as-is to the
                        // response encoder (zero-copy echo).
                        ctx.propagateWrite(
                            HttpResponseHead(
                                status = HttpStatus.OK,
                                headers = HttpHeaders.of(
                                    HttpHeaderName.CONTENT_TYPE to "application/octet-stream",
                                    HttpHeaderName.TRANSFER_ENCODING to "chunked",
                                ),
                            ),
                        )
                        echoStreaming = true
                    }
                    msg.path == "/upload-stream" -> {
                        // Streaming upload: count incoming bytes per chunk, reply
                        // after HttpBodyEnd. No memory aggregation — chunks are
                        // released as they arrive.
                        uploadStreaming = true
                    }
                    msg.path == "/multipart-upload" -> {
                        // Streaming multipart: count bytes (same accounting as
                        // upload-stream) AND scan each chunk for the bench
                        // scenario's known boundary marker so X-Parts-Received
                        // is real. The boundary scan is the cheapest possible
                        // "parse" — it has no header parse and no per-part
                        // allocation, so the bench number is the wire-side
                        // baseline that framework parsers can be compared to.
                        multipartStreaming = true
                        multipartParts = 0
                        multipartCarry = EMPTY_BYTE_ARRAY
                    }
                    msg.path == "/method-echo" -> {
                        // Echo on BodyEnd (not here) so /method-echo + /items/{id}
                        // share the existing emitResponse path with /hello + /large.
                        // Stash the method on the handler so emitResponse can read it.
                        methodEchoMethod = msg.method.name
                    }
                    msg.path.startsWith("/items/") -> {
                        // Same pattern as /method-echo: stash the parsed id and let
                        // emitResponse run on HttpBodyEnd.
                        itemEchoId = msg.path.substring("/items/".length)
                    }
                    msg.path.startsWith("/sse-stream") -> {
                        emitSseStream(ctx, msg)
                        sseStreaming = true
                    }
                }
                // Release the parsed headers' backing recv buffer. See the
                // /ws-* return path above for rationale (io-uring provided
                // buffer ring exhaustion under the PR #596 byte-range
                // storage contract).
                msg.headers.release()
            }
            is HttpBodyEnd -> {
                when {
                    wsUpgradePending -> {
                        // Perform the RFC 6455 §4.2.2 server handshake inline.
                        // The HTTP encoder is still installed so we can write
                        // the 101 head through the normal pipeline path.
                        val acceptKey = computeAcceptKey(wsClientKey!!)
                        // Accept permessage-deflate only when /ws-deflate was
                        // offered the extension. The response then advertises
                        // keel's no-context-takeover policy and the WS decoder
                        // is installed with allowRsv1 = true so RSV1=1
                        // (compressed) frames are not rejected.
                        val acceptDeflate = wsDeflateOffered
                        val headerPairs = buildList {
                            add(HttpHeaderName.UPGRADE to "websocket")
                            add(HttpHeaderName.CONNECTION to "Upgrade")
                            add("Sec-WebSocket-Accept" to acceptKey)
                            if (acceptDeflate) {
                                add("Sec-WebSocket-Extensions" to PipelineHttpWsDeflate.RESPONSE_EXTENSION_HEADER)
                            }
                        }
                        ctx.propagateWrite(
                            HttpResponseHead(
                                status = HttpStatus(101),
                                version = HttpVersion.HTTP_1_1,
                                headers = HttpHeaders.of(*headerPairs.toTypedArray()),
                            ),
                        )
                        ctx.propagateWrite(HttpBodyEnd.EMPTY)
                        ctx.propagateFlush()
                        // Swap the codec: remove HTTP handlers, insert WS codec
                        // before this handler so the pipeline becomes:
                        //   HEAD ↔ ws-encoder ↔ ws-decoder ↔ routing ↔ TAIL
                        ctx.channel.pipeline.remove("decoder")
                        ctx.channel.pipeline.remove("encoder")
                        ctx.channel.pipeline.addBefore(ctx.name, "ws-encoder", WsFrameEncoder())
                        // allowRsv1 = true only when permessage-deflate was
                        // negotiated; the plain /ws-echo path stays strict.
                        ctx.channel.pipeline.addBefore(
                            ctx.name,
                            "ws-decoder",
                            WsFrameDecoder(allowRsv1 = acceptDeflate),
                        )
                        if (acceptDeflate) {
                            wsDeflate = PipelineHttpWsDeflate()
                        }
                        wsUpgradePending = false
                        wsUpgradePath = null
                        wsClientKey = null
                        wsDeflateOffered = false
                        wsEchoMode = true
                    }
                    echoStreaming -> {
                        if (msg.content.readableBytes > 0) {
                            msg.content.retain()
                            ctx.propagateWrite(HttpBody(msg.content))
                            // Backpressure: same flip as the intermediate
                            // HttpBody path. A fixed-length body that ends
                            // in one chunk arrives here (not as HttpBody +
                            // HttpBodyEnd.EMPTY), so the watermark check
                            // belongs here too.
                            if (!readsPausedByBackpressure && !ctx.channel.isWritable) {
                                readsPausedByBackpressure = true
                                ctx.channel.pauseReads()
                            }
                        }
                        ctx.propagateWrite(HttpBodyEnd.EMPTY)
                        ctx.propagateFlush()
                        echoStreaming = false
                    }
                    uploadStreaming -> {
                        uploadBytes += msg.content.readableBytes
                        emitUploadAck(ctx)
                        uploadStreaming = false
                    }
                    multipartStreaming -> {
                        if (msg.content.readableBytes > 0) {
                            uploadBytes += msg.content.readableBytes
                            scanMultipartChunk(msg.content)
                        }
                        emitMultipartAck(ctx)
                        multipartStreaming = false
                    }
                    sseStreaming -> {
                        // SSE response was already emitted on HttpRequestHead; the
                        // GET body is empty so HttpBodyEnd just closes the request
                        // message. Do NOT send a second response — that would be an
                        // HTTP/1.1 keep-alive violation (unsolicited response).
                        sseStreaming = false
                        currentPath = null
                    }
                    else -> emitResponse(ctx)
                }
                msg.content.release()
            }
            is WsFrame -> {
                if (wsEchoMode) {
                    when (msg.opcode) {
                        WsOpcode.PING -> {
                            // RFC 6455 §5.5.3: respond to PING with PONG; server
                            // frames must not be masked (§5.3).
                            ctx.propagateWrite(WsFrame.pong(msg.payload))
                            ctx.propagateFlush()
                        }
                        WsOpcode.PONG -> Unit // unsolicited PONG — discard
                        WsOpcode.CLOSE -> {
                            // RFC 6455 §5.5.1: echo the CLOSE frame back with the
                            // same code and reason, then stop accepting frames.
                            ctx.propagateWrite(
                                WsFrame(fin = true, opcode = WsOpcode.CLOSE, payload = msg.payload),
                            )
                            ctx.propagateFlush()
                            wsEchoMode = false
                            wsDeflate?.close()
                            wsDeflate = null
                        }
                        else -> {
                            // TEXT / BINARY / CONTINUATION: echo back.
                            // RFC 6455 §5.3 forbids the server from masking;
                            // strip the client mask key before sending.
                            val deflate = wsDeflate
                            val outgoing = when {
                                // RSV1=1 — a permessage-deflate compressed
                                // frame. Genuinely decompress then recompress
                                // for the echo: passing the compressed bytes
                                // through verbatim would not exercise the
                                // server-side deflate path. The bench workload
                                // uses single-frame messages, so a per-frame
                                // round-trip is sufficient.
                                msg.rsv1 && deflate != null -> {
                                    val inflated = deflate.decompress(msg.payload)
                                    val recompressed = deflate.compress(inflated)
                                    WsFrame(
                                        fin = true,
                                        rsv1 = true,
                                        opcode = msg.opcode,
                                        payload = recompressed,
                                    )
                                }
                                // RSV1=0 — client sent the message
                                // uncompressed; echo it uncompressed.
                                msg.maskKey != null -> msg.copy(maskKey = null)
                                else -> msg
                            }
                            ctx.propagateWrite(outgoing)
                            ctx.propagateFlush()
                            // Same backpressure flip as the HTTP echo path:
                            // a fast peer that spams large frames must not
                            // be allowed to grow the outbound queue without
                            // limit.
                            if (!readsPausedByBackpressure && !ctx.channel.isWritable) {
                                readsPausedByBackpressure = true
                                ctx.channel.pauseReads()
                            }
                        }
                    }
                }
            }
            is HttpBody -> {
                when {
                    echoStreaming -> {
                        // Zero-copy: pass the body chunk IoBuf directly to the
                        // response encoder. The IoBuf is a platform-native slice
                        // (NativeIoBuf/DirectIoBuf) from allocator.slice(), so
                        // it is transport-compatible.
                        msg.content.retain()
                        ctx.propagateWrite(HttpBody(msg.content))
                        // Backpressure: if writing pushed us past the high
                        // watermark, stop the engine's socket drain so kernel
                        // `rcvbuf` fills and TCP flow control reaches the
                        // sender. `onWritabilityChanged` re-arms reads.
                        if (!readsPausedByBackpressure && !ctx.channel.isWritable) {
                            readsPausedByBackpressure = true
                            ctx.channel.pauseReads()
                        }
                    }
                    uploadStreaming -> {
                        uploadBytes += msg.content.readableBytes
                    }
                    multipartStreaming -> {
                        uploadBytes += msg.content.readableBytes
                        scanMultipartChunk(msg.content)
                    }
                }
                msg.content.release()
            }
            else -> ctx.propagateRead(msg)
        }
    }

    override fun onInactive(ctx: PipelineHandlerContext) {
        // Release the permessage-deflate sessions on a TCP-level teardown
        // (a client that drops the connection without a CLOSE handshake).
        // The CLOSE-frame path already releases them; this guards the
        // no-handshake case so the native Deflater / Inflater contexts are
        // not leaked.
        wsDeflate?.close()
        wsDeflate = null
        // Drop the SSE emission state (its IoBufs were never allocated;
        // only `count` / `payload` were retained) and reset the pause flag
        // silently. The transport is going away — calling resumeReads here
        // would be incorrect.
        pendingSseEmission = null
        readsPausedByBackpressure = false
        ctx.propagateInactive()
    }

    /**
     * Engine callback fired when [io.github.fukusaka.keel.pipeline.PipelinedChannel.isWritable]
     * changes state. Two resumable paths consume this signal:
     *
     * 1. **SSE emission** — `pumpSseStream` parked the remaining count when
     *    the watermark closed; here we drain the remainder.
     * 2. **Echo / WS-echo reads** — `pauseReads()` was issued when the write
     *    side filled; re-arm reads now that the drain has caught up.
     */
    override fun onWritabilityChanged(ctx: PipelineHandlerContext, isWritable: Boolean) {
        if (!isWritable) return
        // Resume a parked SSE pump first so its newly-emitted frames don't
        // immediately re-trigger pauseReads via the catchup path below.
        val parked = pendingSseEmission
        if (parked != null) {
            pumpSseStream(ctx, parked)
        }
        if (readsPausedByBackpressure) {
            readsPausedByBackpressure = false
            ctx.channel.resumeReads()
        }
    }

    private fun emitResponse(ctx: PipelineHandlerContext) {
        val response = when {
            currentPath == "/hello" -> PipelineHttpResponses.hello
            currentPath == "/large" -> PipelineHttpResponses.large
            methodEchoMethod != null -> HttpResponse.ok("ok", contentType = "text/plain").apply {
                headers.add("X-Echo-Method", methodEchoMethod!!)
            }
            itemEchoId != null -> HttpResponse.ok("ok", contentType = "text/plain").apply {
                headers.add("X-Item-Id", itemEchoId!!)
            }
            else -> HttpResponse.notFound()
        }
        currentPath = null
        methodEchoMethod = null
        itemEchoId = null
        ctx.propagateWrite(response)
        ctx.propagateFlush()
    }

    private fun emitUploadAck(ctx: PipelineHandlerContext) {
        ctx.propagateWrite(
            HttpResponse.ok("ok", contentType = "text/plain").apply {
                headers.add("X-Bytes-Received", uploadBytes.toString())
            },
        )
        ctx.propagateFlush()
        currentPath = null
    }

    private fun emitMultipartAck(ctx: PipelineHandlerContext) {
        // The k6 body has (parts + 1) boundary marker occurrences: one
        // before each part plus the trailing `--boundary--`. Subtract 1
        // so X-Parts-Received matches the client's PARTS env. Clamp to
        // 0 in case the body was empty or malformed (defensive).
        val reportedParts = (multipartParts - 1).coerceAtLeast(0)
        ctx.propagateWrite(
            HttpResponse.ok("ok", contentType = "text/plain").apply {
                headers.add("X-Parts-Received", reportedParts.toString())
                headers.add("X-Bytes-Received", uploadBytes.toString())
            },
        )
        ctx.propagateFlush()
        currentPath = null
    }

    /**
     * Counts occurrences of the bench scenario's known boundary marker
     * across this chunk plus any carry from the previous chunk. The k6
     * `multipart.js` builds the body with `--KeelBenchBoundaryV1` as the
     * boundary; counting opening boundaries (one per part) plus the
     * trailing closing boundary `--KeelBenchBoundaryV1--` matches the
     * `parts + 1` total. We subtract 1 in [emitMultipartAck] (well, here
     * before reporting) so `X-Parts-Received` matches the client's
     * `PARTS` env.
     *
     * The carry buffer holds the last `(boundary.size - 1)` bytes from
     * the previous chunk so a boundary that straddles a chunk boundary
     * is still detected. For the bench's typical chunk sizes (single-
     * chunk multipart bodies of a few KB) the carry rarely matters but
     * keeps the scanner correct under arbitrary chunking.
     */
    private fun scanMultipartChunk(content: io.github.fukusaka.keel.buf.IoBuf) {
        val n = content.readableBytes
        if (n == 0) return
        // Copy IoBuf bytes into a ByteArray, then delegate to the pure
        // helper (testable in isolation, see BenchmarkRouteSupportTest).
        val chunk = ByteArray(n)
        for (i in 0 until n) {
            chunk[i] = content.getByte(content.readerIndex + i)
        }
        val result = scanMultipartBoundaries(
            carry = multipartCarry,
            chunk = chunk,
            boundary = BENCHMARK_MULTIPART_BOUNDARY,
        )
        multipartParts += result.count
        multipartCarry = result.carry
    }

    /**
     * SSE handler for the `pipeline-http-*` engines.
     *
     * **Per-frame flush is intentional and load-bearing.** Prior to PR
     * #440 the loop did `propagateWrite(HttpBody(buf))` × N + a
     * single trailing `propagateFlush`, which let the engine batch all
     * 100 chunks into one socket write. The bench then reported a 4-5×
     * inflated `pipeline-http-*` SSE row vs the `ktor-keel-*` Ktor path
     * (which had always called `flush()` per frame from
     * [BenchmarkModule]). That artefact is what the PR #440 fix removes —
     * per-frame `propagateFlush` after every `propagateWrite(HttpBody)`
     * makes the bench measure real per-event throughput.
     *
     * Payload format (`data: <size×x>\n\n`) is shared verbatim with the
     * Ktor handler in [BenchmarkModule] and the cross-language reference
     * servers (rust-bench / go-bench / swift-bench / zig-bench / spring
     * after PR #442) so k6 sse.js can verify body length as
     * `count * (6 + size + 2)` regardless of engine.
     */
    private fun emitSseStream(ctx: PipelineHandlerContext, head: HttpRequestHead) {
        val params = head.queryString.orEmpty()
        val count = parseBenchmarkQueryInt(params, "count") ?: BENCHMARK_SSE_DEFAULT_COUNT
        val size = parseBenchmarkQueryInt(params, "size") ?: BENCHMARK_SSE_DEFAULT_SIZE
        ctx.propagateWrite(
            HttpResponseHead(
                status = HttpStatus.OK,
                headers = HttpHeaders.of(
                    HttpHeaderName.CONTENT_TYPE to "text/event-stream",
                    HttpHeaderName.TRANSFER_ENCODING to "chunked",
                ),
            ),
        )
        val payload = "data: ${"x".repeat(size)}\n\n".encodeToByteArray()
        pumpSseStream(ctx, SseEmissionState(count, payload))
    }

    /**
     * Emits the remaining SSE frames in [state] while
     * [io.github.fukusaka.keel.pipeline.PipelinedChannel.isWritable] holds.
     *
     * If the watermark closes mid-loop the function returns with
     * [pendingSseEmission] holding the remainder; [onWritabilityChanged]
     * resumes the pump when the engine drains. The terminal
     * [HttpBodyEnd.EMPTY] + flush only fire after the loop has emitted all
     * `state.total` frames, so a client that disconnects mid-stream sees a
     * truncated chunked response (the engine close path handles teardown).
     */
    private fun pumpSseStream(ctx: PipelineHandlerContext, state: SseEmissionState) {
        pendingSseEmission = state
        while (state.emitted < state.total) {
            if (!ctx.channel.isWritable) {
                // Park; onWritabilityChanged resumes from here.
                return
            }
            val buf = ctx.channel.allocator.allocate(state.payload.size)
            buf.writeByteArray(state.payload, 0, state.payload.size)
            ctx.propagateWrite(HttpBody(buf))
            // PR #440 — flush per frame so the bench measures real
            // per-event throughput, not bulk delivery. Removing this turns
            // the `pipeline-http-*` SSE row back into the inflated
            // batching number.
            ctx.propagateFlush()
            state.emitted++
        }
        ctx.propagateWrite(HttpBodyEnd.EMPTY)
        ctx.propagateFlush()
        pendingSseEmission = null
        sseStreaming = false
        currentPath = null
    }

}

/**
 * Mutable per-request state for the SSE emission pump. `total` and `payload`
 * stay constant for the lifetime of the request; `emitted` advances each
 * time the pump drains a frame so a subsequent re-entry (via
 * `onWritabilityChanged`) picks up exactly where the watermark interrupted
 * us.
 */
private class SseEmissionState(val total: Int, val payload: ByteArray) {
    var emitted: Int = 0
}

private fun String?.equalsIgnoreCase(other: String): Boolean =
    this != null && this.equals(other, ignoreCase = true)

/**
 * Returns true when [this] request head carries valid RFC 6455 §4.1
 * WebSocket upgrade markers: `Upgrade: websocket`, `Connection: Upgrade`
 * (comma-tolerant, case-insensitive), `Sec-WebSocket-Version: 13`, and a
 * well-formed 16-byte `Sec-WebSocket-Key`.
 */
private fun HttpRequestHead.isWebSocketUpgrade(): Boolean {
    if (!headers.getString(HttpHeaderName.UPGRADE).equalsIgnoreCase("websocket")) return false
    val connection = headers.getString(HttpHeaderName.CONNECTION) ?: return false
    if (!connection.split(',').any { it.trim().equalsIgnoreCase("upgrade") }) return false
    if (headers.getString("Sec-WebSocket-Version") != "13") return false
    val key = headers.getString("Sec-WebSocket-Key") ?: return false
    return validateClientKey(key)
}
