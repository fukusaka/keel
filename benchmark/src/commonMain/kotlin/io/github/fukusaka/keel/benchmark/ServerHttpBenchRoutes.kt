package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.buf.defaultAllocator
import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseHead
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.codec.http.HttpVersion
import io.github.fukusaka.keel.compression.zlib.DeflateCodec
import io.github.fukusaka.keel.compression.zlib.GzipCodec
import io.github.fukusaka.keel.server.http.Asset
import io.github.fukusaka.keel.server.http.AssetSource
import io.github.fukusaka.keel.server.http.Middleware
import io.github.fukusaka.keel.server.http.dsl.KeelHttpServerBuilder
import io.github.fukusaka.keel.server.http.header
import io.github.fukusaka.keel.server.websocket.WsMessage
import io.github.fukusaka.keel.server.websocket.WsSession
import io.github.fukusaka.keel.server.websocket.dsl.webSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlin.time.Instant

private val EMPTY_BODY = ByteArray(0)

/**
 * Background scope for the `/xthread` route's off-EventLoop chunk release.
 * Releasing on [Dispatchers.Default] — not the EventLoop the buffer was
 * allocated on — is what makes the pooled buffer's return cross-thread.
 * [SupervisorJob] so one release failure does not cancel the rest.
 * Process-lifetime; not closed (the benchmark binary exits via its signal handler).
 */
private val xthreadReleaseScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

/**
 * Installs the bench's compression configuration on the
 * [KeelHttpServerBuilder] when [enabled] is true. Registers
 * [GzipCodec] + [DeflateCodec] as the outbound encoders (gzip wins
 * priority tie-break, mirroring PipelineHttpRoutes' default), accepts
 * the built-in pre-compressed MIME exclusions, and enables inbound
 * request decompression so the `compression-upload` scenario's gzip
 * body is decoded before the route handler reads `X-Bytes-Received`.
 *
 * Calling site is the bench engine startup
 * (`server-http-{transport}` benchmarks); the `--compression=true`
 * CLI flag drives [enabled].
 */
fun KeelHttpServerBuilder.installBenchCompression(enabled: Boolean) {
    if (!enabled) return
    compression {
        encoder(GzipCodec, priority = 1)
        encoder(DeflateCodec, priority = 0)
        requestDecompression {
            // bench's compression-upload fixture is ~9:1 ratio with payloads up to
            // ~100 KB compressed; lift the 1 MiB default to keep room for larger
            // fixtures without hitting RequestDecompressionLimitException.
            limit = 10L * 1024 * 1024
        }
    }
}

/**
 * Installs [depth] pass-through middlewares on the [KeelHttpServerBuilder]
 * so the bench can measure the per-hop dispatch cost of the
 * [Middleware] chain. Each installed middleware does nothing but call
 * `next()`, so sweeping `--middleware-depth` over `/hello` isolates the
 * framework's middleware overhead: the throughput delta per added depth
 * is the cost of one extra chain hop.
 *
 * No-op when [depth] <= 0 (the default). `pipeline-http-*` engines have
 * no framework middleware concept and never call this; they serve as the
 * depth-0 floor in the sweep.
 */
fun KeelHttpServerBuilder.installBenchMiddleware(depth: Int) {
    repeat(depth.coerceAtLeast(0)) {
        install(Middleware { _, next -> next() })
    }
}

/**
 * Installs the `KeelHttpServer` feature micro-bench surface driven by the
 * `BenchmarkConfig` flags: the [Middleware] chain depth plus the router
 * scale, predicate, path-parameter, and static-file sub-benches. Each is
 * a no-op at its zero default, so a plain `/hello` run is unaffected and
 * a sweep enables exactly the one feature it measures.
 *
 * Called by every `server-http-*` engine startup (alongside
 * [installBenchCompression] / [installStreamingBenchRoutes]).
 * `pipeline-http-*` engines have no framework feature surface and never
 * call this — they stay the raw-codec floor.
 */
fun KeelHttpServerBuilder.installBenchFeatureRoutes(config: BenchmarkConfig) {
    installBenchMiddleware(config.middlewareDepth)
    installBenchRouterScale(config.routerExtraRoutes, config.routerGrouped)
    installBenchPredicates(config.predicateCount)
    installBenchPathParam(config.pathParamMode)
    installBenchStaticFile(config.staticFileBytes)
}

/**
 * Registers [extraRoutes] synthetic GET routes under `/bench-route/<i>` so
 * a sweep can grow the route table and measure how the router's match
 * cost scales. Each route returns the standard `/hello` body.
 *
 * When [grouped] is false the routes are flat (`get("/bench-route/$i")`);
 * when true they are nested under a single `route("/bench-route") { … }`
 * group. Both compile to the same segment trie, so the pair isolates DSL
 * registration cost from per-request match cost (which is identical) —
 * the bench confirms grouping sugar is request-path zero-cost.
 *
 * No-op when [extraRoutes] <= 0. To probe sibling lookup among the N
 * literal children the bench hits `/bench-route/<N/2>`; to probe
 * unrelated-path overhead it hits `/hello`.
 */
fun KeelHttpServerBuilder.installBenchRouterScale(extraRoutes: Int, grouped: Boolean) {
    val n = extraRoutes.coerceAtLeast(0)
    if (n == 0) return
    if (grouped) {
        route("/bench-route") {
            repeat(n) { i -> get("/$i") { call -> call.respond(PipelineHttpResponses.hello) } }
        }
    } else {
        repeat(n) { i -> get("/bench-route/$i") { call -> call.respond(PipelineHttpResponses.hello) } }
    }
}

/**
 * Registers [count] header-guarded handlers on `/bench-predicate`, each
 * gated by a distinct `X-Bench-Sel: v<i>` predicate, plus a final
 * unguarded catch-all. A client sending `X-Bench-Sel: v<count-1>` forces
 * the router to evaluate every predicate before the last one accepts, so
 * the throughput delta per added [count] is the per-predicate evaluation
 * cost. All handlers return the standard `/hello` body.
 *
 * No-op when [count] <= 0.
 */
fun KeelHttpServerBuilder.installBenchPredicates(count: Int) {
    val n = count.coerceAtLeast(0)
    if (n == 0) return
    repeat(n) { i ->
        get("/bench-predicate", predicate = header("X-Bench-Sel", "v$i")) { call ->
            call.respond(PipelineHttpResponses.hello)
        }
    }
    // Unguarded catch-all kept last so a request whose X-Bench-Sel matches
    // none of the predicates still gets a 200 rather than a 404.
    get("/bench-predicate") { call -> call.respond(PipelineHttpResponses.hello) }
}

/**
 * Registers the `/bench-param/:id` route with the path-parameter
 * constraint selected by [mode] (`"plain"` / `"int"` / `"uuid"` /
 * `"regex"`; `"none"` disables the route). The handler echoes the
 * extracted id via `X-Item-Id`, so sweeping the mode against a matching
 * value isolates the constraint-check overhead on the extraction hot
 * path versus the unconstrained `:id` baseline.
 */
fun KeelHttpServerBuilder.installBenchPathParam(mode: String) {
    val pattern = when (mode) {
        "plain" -> "/bench-param/:id"
        "int" -> "/bench-param/:id(int)"
        "uuid" -> "/bench-param/:id(uuid)"
        "regex" -> "/bench-param/:id(^[a-z0-9-]+\$)"
        else -> return
    }
    get(pattern) { call ->
        call.respond(
            HttpResponse(
                status = HttpStatus.OK,
                version = HttpVersion.HTTP_1_1,
                headers = HttpHeaders.of(
                    HttpHeaderName.CONTENT_LENGTH to "0",
                    "X-Item-Id" to call.pathParameters["id"].orEmpty(),
                ),
                body = EMPTY_BODY,
            ),
        )
    }
}

/**
 * Serves an in-memory static asset of [bytes] bytes at `/bench-static`
 * (no-op when [bytes] <= 0). Backed by [BenchAssetSource] so the bench
 * can exercise the static-file serve path — full `200`, single-range
 * `206` (`Range: bytes=…`), and conditional GET (`If-None-Match` against
 * the fixed weak ETag) — against an asset of known size without touching
 * the filesystem (keeps the bench portable across native / JS / JVM).
 */
fun KeelHttpServerBuilder.installBenchStaticFile(bytes: Int) {
    if (bytes <= 0) return
    staticAssets("/bench-static", BenchAssetSource(bytes))
}

/**
 * Fixed-size in-memory [AssetSource] for the static-file bench. Resolves
 * every path to the same [BenchAsset] of [size] bytes filled with `'x'`.
 */
private class BenchAssetSource(private val size: Int) : AssetSource {
    private val asset = BenchAsset(size)
    override fun resolve(path: String): Asset = asset
}

/**
 * In-memory [Asset] of [byteSize] bytes (all `'x'`) with a fixed weak
 * ETag so `If-None-Match` conditional GET is exercisable. [open] returns
 * a fresh [Buffer] over the requested `[offset, offset + length)` slice,
 * so Range requests are served without re-reading any backing store.
 */
private class BenchAsset(private val byteSize: Int) : Asset {
    private val data = ByteArray(byteSize) { 'x'.code.toByte() }

    override val size: Long = byteSize.toLong()
    override val contentType: String = "application/octet-stream"
    override val lastModified: Instant? = null
    override val etag: String = "W/\"bench-$byteSize\""

    override fun open(offset: Long, length: Long): RawSource {
        val start = offset.toInt().coerceIn(0, byteSize)
        val end = (offset + length).toInt().coerceIn(start, byteSize)
        return Buffer().apply { write(data, start, end) }
    }
}

/**
 * Installs the streaming benchmark routes used by `bench-stream-one.sh`
 * on a [KeelHttpServerBuilder]. Mirrors the pipeline-http handler's
 * route surface so a sweep can compare server-http (`KeelHttpServer`
 * DSL = v1.0 recommended API) against pipeline-http (raw codec, floor)
 * and the Ktor adapters on the same transport.
 *
 * Response compression and request decompression are configured by the
 * caller via [installBenchCompression] (gated on the `--compression=true`
 * CLI flag); the route handlers themselves are agnostic — the
 * pipeline-level handlers transform the bytes transparently.
 */
fun KeelHttpServerBuilder.installStreamingBenchRoutes() {
    get("/hello") { call -> call.respond(PipelineHttpResponses.hello) }
    get("/large") { call -> call.respond(PipelineHttpResponses.large) }

    // POST /upload-stream — discards request body chunk-by-chunk, replies
    // with `X-Bytes-Received: <total>`. Mirrors PipelineHttpRoutes.
    post("/upload-stream") { call ->
        var total = 0L
        while (true) {
            val chunk = call.receiveChunk() ?: break
            total += chunk.readableBytes
            chunk.release()
        }
        call.respond(
            HttpResponse(
                status = HttpStatus.OK,
                version = HttpVersion.HTTP_1_1,
                headers = HttpHeaders.of(
                    HttpHeaderName.CONTENT_LENGTH to "0",
                    "X-Bytes-Received" to total.toString(),
                ),
                body = EMPTY_BODY,
            ),
        )
    }

    // POST /upload-aggregate — reads the FULL request body via receiveBytes(),
    // which collects the pooled body chunks, holds them, then copies them into
    // one heap ByteArray sized once from the total (one alloc, no double-realloc).
    // Pairs with /upload-stream (receiveChunk drain, pooled, no hold/copy) for an
    // A/B of the GC cost of holding + materialising the body as a heap ByteArray
    // vs draining pooled chunks. Same response shape so only the read path differs.
    post("/upload-aggregate") { call ->
        val body = call.receiveBytes()
        call.respond(
            HttpResponse(
                status = HttpStatus.OK,
                version = HttpVersion.HTTP_1_1,
                headers = HttpHeaders.of(
                    HttpHeaderName.CONTENT_LENGTH to "0",
                    "X-Bytes-Received" to body.size.toString(),
                ),
                body = EMPTY_BODY,
            ),
        )
    }

    // POST /xthread — same body-drain as /upload-stream, but releases each
    // chunk on a background Dispatchers.Default coroutine instead of the
    // EventLoop. Releasing off the EventLoop is what makes the pooled buffer's
    // return cross-thread, so this route deliberately exercises the
    // owner-capture locked-push return path under a non-zero cross-thread rate.
    // Pairs with /upload-stream (identical loop, EventLoop release) for an A/B
    // measurement of whether that path's cost shows up in throughput / alloc.
    // The handler does not await the background release (fire-and-forget); the
    // buffer's atomic refcount keeps it safe.
    post("/xthread") { call ->
        var total = 0L
        while (true) {
            val chunk = call.receiveChunk() ?: break
            total += chunk.readableBytes
            xthreadReleaseScope.launch { chunk.release() }
        }
        call.respond(
            HttpResponse(
                status = HttpStatus.OK,
                version = HttpVersion.HTTP_1_1,
                headers = HttpHeaders.of(
                    HttpHeaderName.CONTENT_LENGTH to "0",
                    "X-Bytes-Received" to total.toString(),
                ),
                body = EMPTY_BODY,
            ),
        )
    }

    // POST /upload-slow — same as /upload-stream but inserts a small
    // suspend between chunks so wrk/k6's slow-upload scenario sees the
    // server cooperating with throttled clients. Mirrors PipelineHttpRoutes
    // `/upload-slow` (server-side delay, not client throttling).
    post("/upload-slow") { call ->
        var total = 0L
        while (true) {
            val chunk = call.receiveChunk() ?: break
            total += chunk.readableBytes
            chunk.release()
            delay(1)
        }
        call.respond(
            HttpResponse(
                status = HttpStatus.OK,
                version = HttpVersion.HTTP_1_1,
                headers = HttpHeaders.of(
                    HttpHeaderName.CONTENT_LENGTH to "0",
                    "X-Bytes-Received" to total.toString(),
                ),
                body = EMPTY_BODY,
            ),
        )
    }

    // POST /multipart-upload — streams body chunk-by-chunk, scans each
    // chunk for the benchmark's known boundary marker
    // ("--KeelBenchBoundaryV1") and reports the count via
    // `X-Parts-Received`. Mirrors PipelineHttpRoutes.
    post("/multipart-upload") { call ->
        var total = 0L
        var parts = 0
        var carry: ByteArray = EMPTY_BYTE_ARRAY
        while (true) {
            val chunk = call.receiveChunk() ?: break
            val len = chunk.readableBytes
            total += len
            val chunkBytes = ByteArray(len)
            chunk.readByteArray(chunkBytes, 0, len)
            chunk.release()
            val scan = scanMultipartBoundaries(
                carry = carry,
                chunk = chunkBytes,
                boundary = BENCHMARK_MULTIPART_BOUNDARY,
            )
            parts += scan.count
            carry = scan.carry
        }
        call.respond(
            HttpResponse(
                status = HttpStatus.OK,
                version = HttpVersion.HTTP_1_1,
                headers = HttpHeaders.of(
                    HttpHeaderName.CONTENT_LENGTH to "0",
                    "X-Bytes-Received" to total.toString(),
                    // PipelineHttpRoutes subtracts 1 from the raw boundary
                    // count: the multipart wire shape has N parts but
                    // N+1 boundary markers (the closing `--BOUNDARY--`
                    // is the +1). k6's `parts received correct` check
                    // expects N, so report `parts - 1`.
                    "X-Parts-Received" to (parts - 1).coerceAtLeast(0).toString(),
                ),
                body = EMPTY_BODY,
            ),
        )
    }

    // GET /sse-stream?count=N&size=M — emits N chunks of `data: <M
    // bytes>\n\n` via chunked transfer-encoding (default 100×1024 per
    // BENCHMARK_SSE_DEFAULT_*). Mirrors PipelineHttpRoutes — each chunk's
    // wire size is `data: ` (6) + payload (M) + `\n\n` (2) so the k6 SSE
    // scenario's body-length check passes.
    get("/sse-stream") { call ->
        val count = (call.queryParameters["count"]?.toIntOrNull() ?: BENCHMARK_SSE_DEFAULT_COUNT).coerceAtLeast(1)
        val size = (call.queryParameters["size"]?.toIntOrNull() ?: BENCHMARK_SSE_DEFAULT_SIZE).coerceAtLeast(1)
        val frameSize = 6 + size + 2
        val frame = ByteArray(frameSize).also {
            it[0] = 'd'.code.toByte(); it[1] = 'a'.code.toByte(); it[2] = 't'.code.toByte()
            it[3] = 'a'.code.toByte(); it[4] = ':'.code.toByte(); it[5] = ' '.code.toByte()
            for (i in 6 until 6 + size) it[i] = 'x'.code.toByte()
            it[6 + size] = '\n'.code.toByte(); it[7 + size] = '\n'.code.toByte()
        }
        call.respondStream(
            HttpResponseHead(
                status = HttpStatus.OK,
                version = HttpVersion.HTTP_1_1,
                headers = HttpHeaders.of(
                    HttpHeaderName.CONTENT_TYPE to "text/event-stream",
                    HttpHeaderName.TRANSFER_ENCODING to "chunked",
                    HttpHeaderName.CACHE_CONTROL to "no-cache",
                ),
            ),
        ) { sink ->
            val allocator = defaultAllocator()
            repeat(count) {
                val buf = allocator.allocate(frameSize)
                buf.writeByteArray(frame, 0, frameSize)
                sink.write(buf)
            }
        }
    }

    // /method-echo — multi-method echo. The k6 scenario verifies status
    // 200 + `X-Echo-Method: <UPPERCASE METHOD>` header, so the body is
    // empty and the response head carries the method via header.
    for (
        method in arrayOf(
            HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE,
            HttpMethod.PATCH, HttpMethod.OPTIONS, HttpMethod.HEAD,
        )
    ) {
        route(method, "/method-echo") { call ->
            call.respond(
                HttpResponse(
                    status = HttpStatus.OK,
                    version = HttpVersion.HTTP_1_1,
                    headers = HttpHeaders.of(
                        HttpHeaderName.CONTENT_LENGTH to "0",
                        "X-Echo-Method" to call.method.name,
                    ),
                    body = EMPTY_BODY,
                ),
            )
        }
    }

    // GET /items/:id — path-parameter echo via `X-Item-Id` header (the
    // k6 scenario reads the header, not the body) so the bench surfaces
    // the engine's pathParameters extraction cost.
    get("/items/:id") { call ->
        call.respond(
            HttpResponse(
                status = HttpStatus.OK,
                version = HttpVersion.HTTP_1_1,
                headers = HttpHeaders.of(
                    HttpHeaderName.CONTENT_LENGTH to "0",
                    "X-Item-Id" to call.pathParameters["id"].orEmpty(),
                ),
                body = EMPTY_BODY,
            ),
        )
    }
}

/**
 * Installs the WebSocket benchmark endpoints. Three routes (echo paths
 * for `bench-stream-one.sh`'s WS scenarios):
 *
 * - `/ws-echo` — plain echo (no compression). Used by `ws-echo`,
 *   `ws-large`, `ws-slow-consumer` scenarios (all connect to the same
 *   `/ws-echo` URL per the k6 scripts).
 * - `/ws-fragment` — plain echo (no compression). Used by the
 *   `ws-fragment` scenario (wsbench Go client).
 * - `/ws-deflate` — echo with `permessage-deflate` (DeflateCodec). Used
 *   by the `ws-deflate` scenario.
 * - `/ws-held/:n/:mode` — held-pooled workload for the allocator
 *   measurement (see [runHeldPooledEcho]). Holds `n` received messages
 *   before echoing the evicted oldest, so `n` pooled payloads per
 *   connection stay outstanding and stress the central allocator.
 *
 * Two `webSockets { … }` blocks are registered: one without compression
 * for `/ws-echo` + `/ws-fragment` + `/ws-held`, one with `DeflateCodec`
 * for `/ws-deflate`. Endpoints inside a `webSockets(codec)` group share
 * one upgrade pipeline so registering both routes together stays
 * cheap.
 */
fun KeelHttpServerBuilder.installWebSocketBenchRoutes() {
    webSockets {
        webSocket("/ws-echo") { for (m in incoming) send(m) }
        webSocket("/ws-fragment") { for (m in incoming) send(m) }
        webSocket("/ws-held/:n/:mode") { runHeldPooledEcho() }
    }
    webSockets(DeflateCodec) {
        webSocket("/ws-deflate") { for (m in incoming) send(m) }
    }
}

/** Held-message ring size when `:n` is absent or unparseable. */
private const val DEFAULT_HELD_MESSAGES = 64

/** Lower bound on the held-message ring (`:n` is clamped to this minimum). */
private const val MIN_HELD_MESSAGES = 1

/** Upper bound on the held-message ring (guards against an OOM from a huge `:n`). */
private const val MAX_HELD_MESSAGES = 8192

/**
 * Held-pooled WebSocket workload for the allocator-capability measurement.
 *
 * Keeps a steady-state ring of `n` received messages **held** before echoing
 * the evicted oldest, so at any instant `n` pooled payloads per connection stay
 * outstanding (not returned to the pool). Across many connections this depletes
 * the per-EventLoop freelist reserve and forces `ChunkArena.carve` under the
 * central `ArenaLock` — the cost a sharded central allocator
 * would remove. Run the server with `--profile-alloc` to read the per-size-class
 * carve / miss% under the held working set.
 *
 * `:mode` selects what is held (the A/B):
 * - `chunks` (default) — hold the pooled [WsMessage.BinaryChunks] as delivered
 *   (the zero-copy path; this is what keeps pooled payloads outstanding).
 * - `bytes` — flatten each message to a heap [ByteArray] (releasing the pooled
 *   payload immediately) and hold that. The control arm: identical workload
 *   shape, but the held working set is GC heap rather than pooled, so the
 *   central-carve delta between `chunks` and `bytes` isolates the held-pooled
 *   cost from the workload itself.
 *
 * Iterates [incoming] directly (not [onMessage][WsSession.onMessage]) because a
 * held message must outlive the per-message scope: this handler owns each
 * delivered [WsMessage.BinaryChunks] and releases it via [send] on eviction
 * (ownership transfers to the transport) or, for any payload still held when the
 * connection drops, in the `finally`.
 *
 * `:n` is clamped to [[MIN_HELD_MESSAGES], [MAX_HELD_MESSAGES]].
 */
private suspend fun WsSession.runHeldPooledEcho() {
    val n = (pathParameters["n"]?.toIntOrNull() ?: DEFAULT_HELD_MESSAGES)
        .coerceIn(MIN_HELD_MESSAGES, MAX_HELD_MESSAGES)
    val holdChunks = pathParameters["mode"] != "bytes"
    val held = ArrayDeque<WsMessage>(n + 1)
    try {
        for (message in incoming) {
            held.addLast(if (holdChunks) message else message.flattenedToHeap())
            if (held.size > n) send(held.removeFirst())
        }
    } finally {
        // Connection closed mid-stream: release any pooled payloads still held.
        held.forEach { (it as? WsMessage.BinaryChunks)?.chunks?.release() }
    }
}

/**
 * Flattens a pooled [WsMessage.BinaryChunks] into a heap [WsMessage.Binary],
 * releasing the pooled chunks. Non-pooled messages pass through unchanged.
 */
private fun WsMessage.flattenedToHeap(): WsMessage =
    if (this is WsMessage.BinaryChunks) {
        val out = ByteArray(chunks.totalSize)
        var offset = 0
        chunks.forEach { chunk ->
            val readable = chunk.readableBytes
            chunk.readByteArray(out, offset, readable)
            offset += readable
        }
        chunks.release()
        WsMessage.Binary(out)
    } else {
        this
    }
