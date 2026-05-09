package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.codec.http.HttpBodyEnd
import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpRequestDecoder
import io.github.fukusaka.keel.codec.http.HttpRequestHead
import io.github.fukusaka.keel.codec.http.HttpResponseEncoder
import io.github.fukusaka.keel.codec.http.HttpResponseHead
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.codec.http.HttpVersion
import io.github.fukusaka.keel.codec.websocket.WsFrame
import io.github.fukusaka.keel.codec.websocket.WsFrameDecoder
import io.github.fukusaka.keel.codec.websocket.WsFrameEncoder
import io.github.fukusaka.keel.codec.websocket.WsOpcode
import io.github.fukusaka.keel.codec.websocket.computeAcceptKey
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Regression test for K4: pipeline-http-netty WebSocket echo crashes with SIGKILL (OOM)
 * under sustained load, reporting 0 complete iterations and 0 req/s in the benchmark.
 *
 * The root cause was [io.github.fukusaka.keel.pipeline.TypedInboundHandler] leaking the
 * [io.github.fukusaka.keel.buf.IoBuf] input whenever a transforming handler (such as
 * [WsFrameDecoder]) propagated a different output object. Each inbound WebSocket frame
 * left its Netty `ByteBuf` permanently unreleased; the pool exhausted under 50-VU load
 * and macOS sent SIGKILL.
 *
 * These tests exercise the WS upgrade + echo path using [NettyEngine.bindPipeline] with an
 * inline [WsEchoHandler] that mirrors `BenchmarkRoutingHandler`'s logic — without the full
 * benchmark module. A few connections and frames are enough to verify correctness; pool
 * exhaustion at scale requires sustained high-throughput load and is covered by a separate
 * stress test with `@Tag("stress")`.
 *
 * **Resource discipline (see [TestWsClient])**: every test wraps `HttpClient` use in
 * `newTestWsClient().use { ... }` so that the JDK HTTP client and its executor are torn
 * down deterministically. Without this, fresh `HttpClient.newHttpClient()` instances forked
 * a private selector + executor pair per test that survived the test method, accumulating
 * zombie threads across the suite. On resource-constrained CI runners (GHA macOS Apple
 * Silicon in particular) the accumulated threads multiplied scheduler contention, causing
 * later tests to drift from sub-second runtime towards the outer test timeout.
 */
class NettyPipelineWsEchoTest {

    /**
     * Minimal WS echo handler that mirrors `BenchmarkRoutingHandler`'s `/ws-echo` logic.
     */
    private class WsEchoHandler : InboundHandler {
        private var wsUpgradePending = false
        private var wsClientKey: String? = null
        private var wsEchoMode = false

        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            when (msg) {
                is HttpRequestHead -> {
                    if (msg.path == "/ws-echo" && isWebSocketUpgrade(msg)) {
                        wsUpgradePending = true
                        wsClientKey = msg.headers["Sec-WebSocket-Key"]
                    }
                }
                is HttpBodyEnd -> {
                    if (wsUpgradePending) {
                        val acceptKey = computeAcceptKey(wsClientKey!!)
                        ctx.propagateWrite(
                            HttpResponseHead(
                                status = HttpStatus(101),
                                version = HttpVersion.HTTP_1_1,
                                headers = HttpHeaders.of(
                                    HttpHeaderName.UPGRADE to "websocket",
                                    HttpHeaderName.CONNECTION to "Upgrade",
                                    "Sec-WebSocket-Accept" to acceptKey,
                                ),
                            ),
                        )
                        ctx.propagateWrite(HttpBodyEnd.EMPTY)
                        ctx.propagateFlush()
                        ctx.channel.pipeline.remove("decoder")
                        ctx.channel.pipeline.remove("encoder")
                        ctx.channel.pipeline.addBefore(ctx.name, "ws-encoder", WsFrameEncoder())
                        ctx.channel.pipeline.addBefore(ctx.name, "ws-decoder", WsFrameDecoder())
                        wsUpgradePending = false
                        wsClientKey = null
                        wsEchoMode = true
                    }
                    msg.content.release()
                }
                is WsFrame -> {
                    if (wsEchoMode) {
                        when (msg.opcode) {
                            WsOpcode.CLOSE -> {
                                ctx.propagateWrite(
                                    WsFrame(fin = true, opcode = WsOpcode.CLOSE, payload = msg.payload),
                                )
                                ctx.propagateFlush()
                                wsEchoMode = false
                            }
                            WsOpcode.PING -> {
                                ctx.propagateWrite(WsFrame.pong(msg.payload))
                                ctx.propagateFlush()
                            }
                            else -> {
                                val outgoing = if (msg.maskKey != null) msg.copy(maskKey = null) else msg
                                ctx.propagateWrite(outgoing)
                                ctx.propagateFlush()
                            }
                        }
                    }
                }
                else -> ctx.propagateRead(msg)
            }
        }

        private fun isWebSocketUpgrade(head: HttpRequestHead): Boolean {
            val upgrade = head.headers[HttpHeaderName.UPGRADE] ?: return false
            if (!upgrade.equals("websocket", ignoreCase = true)) return false
            val connection = head.headers[HttpHeaderName.CONNECTION] ?: return false
            if (!connection.split(',').any { it.trim().equals("upgrade", ignoreCase = true) }) return false
            if (head.headers["Sec-WebSocket-Version"] != "13") return false
            return head.headers["Sec-WebSocket-Key"] != null
        }
    }

    /**
     * Test-only [HttpClient] wrapper that owns its executor and shuts both down on [close].
     *
     * Solves the `HttpClient.newHttpClient` resource-leak problem: a freshly-built JDK
     * `HttpClient` forks its own selector thread plus an executor pool, and the JDK 21
     * `HttpClient.close()` API must be called explicitly to release them. Without explicit
     * shutdown the selector + executor threads survived the test method, accumulating across
     * the suite and amplifying scheduler-contention slowdowns on resource-constrained CI
     * runners (the tests then drifted from sub-second locally to multi-minute on GHA macOS).
     *
     * Using a fixed-size daemon executor (instead of the implicit
     * `ForkJoinPool.commonPool()` that `newHttpClient()` falls back to) also keeps `WebSocket.Listener`
     * callbacks off any shared global pool, so callbacks from this test do not interleave with
     * other test classes that might use the common pool.
     */
    private class TestWsClient(
        val http: HttpClient,
        private val executor: ExecutorService,
    ) : AutoCloseable {
        override fun close() {
            http.close()
            executor.shutdown()
            // Bounded; if a callback is genuinely stuck the test harness will surface that
            // separately. 5 s is generous for any in-flight WS callback to drain.
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun newTestWsClient(): TestWsClient {
        val executor = Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "test-ws-client").apply { isDaemon = true }
        }
        val http = HttpClient.newBuilder().executor(executor).build()
        return TestWsClient(http, executor)
    }

    /**
     * Single VU: 1 connection, 3 sequential text echo rounds.
     *
     * Baseline — must pass for the multi-connection tests to be meaningful. All
     * `CompletableFuture.get(...)` blocking calls have been replaced with
     * `kotlinx.coroutines.future.await()` so the surrounding `runTest` dispatch-timeout
     * (default 60 s) drives cancellation if any single op stalls, instead of the
     * per-call blocking timeout silently consuming the budget.
     */
    @Test
    fun `ws-echo single connection echoes text frames`() = runTest {
        val engine = NettyEngine()
        val server = engine.bindPipeline("127.0.0.1", 0) { channel ->
            channel.pipeline.addLast("encoder", HttpResponseEncoder())
            channel.pipeline.addLast("decoder", HttpRequestDecoder())
            channel.pipeline.addLast("ws-echo", WsEchoHandler())
        }
        val port = (server.localAddress as InetSocketAddress).port
        try {
            newTestWsClient().use { client ->
                val echo1 = CompletableFuture<String>()
                val echo2 = CompletableFuture<String>()
                val echo3 = CompletableFuture<String>()
                val echoes = listOf(echo1, echo2, echo3)
                var echoIdx = 0

                val ws = client.http.newWebSocketBuilder()
                    .buildAsync(URI("ws://127.0.0.1:$port/ws-echo"), object : WebSocket.Listener {
                        override fun onOpen(ws: WebSocket) = ws.request(Long.MAX_VALUE)
                        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean) =
                            echoes.getOrNull(echoIdx++)?.complete(data.toString()).let { null }
                    })
                    .await()

                ws.sendText("hello", true).await()
                assertEquals("hello", echo1.await())
                ws.sendText("world", true).await()
                assertEquals("world", echo2.await())
                ws.sendText("keel", true).await()
                assertEquals("keel", echo3.await())
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "").await()
            }
        } finally {
            server.close()
            engine.close()
        }
    }

    /**
     * 2 concurrent VUs each send a text frame and wait for the echo. Verifies that
     * the WS upgrade + echo path functions correctly for multiple simultaneous
     * connections — the multi-channel state-isolation smoke test that complements
     * the deterministic seam-test coverage of K4 IoBuf-leak regression.
     *
     * Connection setup is parallelised via `coroutineScope { ... async { ... }.awaitAll() }`
     * so the two builds run concurrently and the test wall-time is bounded by the
     * slowest connection rather than the sum.
     */
    @Test
    fun `ws-echo two concurrent connections both receive echoes`() = runTest {
        val engine = NettyEngine()
        val server = engine.bindPipeline("127.0.0.1", 0) { channel ->
            channel.pipeline.addLast("encoder", HttpResponseEncoder())
            channel.pipeline.addLast("decoder", HttpRequestDecoder())
            channel.pipeline.addLast("ws-echo", WsEchoHandler())
        }
        val port = (server.localAddress as InetSocketAddress).port
        try {
            newTestWsClient().use { client ->
                suspend fun openWs(): Pair<WebSocket, CompletableFuture<String>> {
                    val echoFuture = CompletableFuture<String>()
                    val pending = StringBuilder()
                    val ws = client.http.newWebSocketBuilder()
                        .buildAsync(URI("ws://127.0.0.1:$port/ws-echo"), object : WebSocket.Listener {
                            override fun onOpen(ws: WebSocket) = ws.request(Long.MAX_VALUE)
                            override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): Nothing? {
                                pending.append(data)
                                if (last) echoFuture.complete(pending.toString())
                                return null
                            }
                            override fun onError(ws: WebSocket, error: Throwable) {
                                echoFuture.completeExceptionally(error)
                            }
                        })
                        .await()
                    return ws to echoFuture
                }

                val (first, second) = coroutineScope {
                    listOf(async { openWs() }, async { openWs() }).awaitAll()
                }
                val (ws1, echo1) = first
                val (ws2, echo2) = second

                ws1.sendText("from-vu1", true)
                ws2.sendText("from-vu2", true)

                assertEquals("from-vu1", echo1.await())
                assertEquals("from-vu2", echo2.await())

                ws1.sendClose(WebSocket.NORMAL_CLOSURE, "").await()
                ws2.sendClose(WebSocket.NORMAL_CLOSURE, "").await()
            }
        } finally {
            server.close()
            engine.close()
        }
    }

    /**
     * 5 concurrent connections, each does 3 echo rounds. Exercises the pipeline
     * under more load to catch intermittent hangs.
     *
     * **K40 (PR #466)**: replaced every blocking `CompletableFuture.get(N, TimeUnit.SECONDS)`
     * with `kotlinx.coroutines.future.await()`, and parallelised connection setup so the
     * outer `withTimeout(60.seconds)` budget could not be hijacked by a stalled blocking
     * `.get()` and the cumulative sequential setup wall-time would not consume the budget
     * on its own.
     *
     * **K40 expansion (this PR)**: the *echo wait + close* phase was still sequential
     * across connections — 5 conn × (3 echo + 1 close) = 20 sequential `await` calls. On
     * a slow CI runner where each `await` resolves in seconds rather than milliseconds the
     * sum could still hit the 60 s outer cap (one observed failure: GHA macOS Apple
     * Silicon, 2026-05-09). Wrap the per-connection echo + close work in
     * `coroutineScope { ... async { ... }.awaitAll() }` so the wall-time is the slowest
     * connection rather than the sum, matching the setup-phase pattern from PR #466.
     */
    @Test
    fun `ws-echo five concurrent connections all complete multiple rounds`(): Unit = runBlocking {
        withTimeout(60.seconds) {
            val engine = NettyEngine()
            val server = engine.bindPipeline("127.0.0.1", 0) { channel ->
                channel.pipeline.addLast("encoder", HttpResponseEncoder())
                channel.pipeline.addLast("decoder", HttpRequestDecoder())
                channel.pipeline.addLast("ws-echo", WsEchoHandler())
            }
            val port = (server.localAddress as InetSocketAddress).port
            try {
                newTestWsClient().use { client ->
                    val connections = coroutineScope {
                        (1..5).map { id ->
                            async {
                                val rounds = 3
                                val futures = (1..rounds).map { CompletableFuture<String>() }
                                var roundIdx = 0
                                val pending = StringBuilder()
                                val ws = client.http.newWebSocketBuilder()
                                    .buildAsync(
                                        URI("ws://127.0.0.1:$port/ws-echo"),
                                        object : WebSocket.Listener {
                                            override fun onOpen(ws: WebSocket) = ws.request(Long.MAX_VALUE)
                                            override fun onText(
                                                ws: WebSocket,
                                                data: CharSequence,
                                                last: Boolean,
                                            ): Nothing? {
                                                pending.append(data)
                                                if (last) {
                                                    futures.getOrNull(roundIdx++)?.complete(pending.toString())
                                                    pending.clear()
                                                }
                                                return null
                                            }
                                            override fun onError(ws: WebSocket, error: Throwable) {
                                                futures.forEach { it.completeExceptionally(error) }
                                            }
                                        },
                                    )
                                    .await()
                                Triple(id, ws, futures)
                            }
                        }.awaitAll()
                    }

                    for ((id, ws, _) in connections) {
                        for (r in 1..3) ws.sendText("vu$id-r$r", true)
                    }

                    coroutineScope {
                        connections.map { (id, ws, futures) ->
                            async {
                                for (r in 1..3) {
                                    assertEquals(
                                        "vu$id-r$r",
                                        futures[r - 1].await(),
                                        "VU$id round $r echo mismatch",
                                    )
                                }
                                ws.sendClose(WebSocket.NORMAL_CLOSURE, "").await()
                            }
                        }.awaitAll()
                    }
                }
            } finally {
                server.close()
                engine.close()
            }
        }
    }
}
