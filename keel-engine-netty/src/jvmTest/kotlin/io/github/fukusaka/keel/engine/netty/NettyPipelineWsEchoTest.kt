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
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals

/**
 * Regression test for K4: pipeline-http-netty WebSocket echo crashes with SIGKILL (OOM)
 * under sustained load, reporting 0 complete iterations and 0 req/s in the benchmark.
 *
 * The root cause was [TypedInboundHandler] leaking the [IoBuf] input whenever a
 * transforming handler (such as [WsFrameDecoder]) propagated a different output object.
 * Each inbound WebSocket frame left its Netty [ByteBuf] permanently unreleased; the pool
 * exhausted under 50-VU load and macOS sent SIGKILL.
 *
 * These tests exercise the WS upgrade + echo path using [NettyEngine.bindPipeline] with an
 * inline [WsEchoHandler] that mirrors [BenchmarkRoutingHandler]'s logic — without the full
 * benchmark module. A few connections and frames are enough to verify correctness; pool
 * exhaustion requires sustained high-throughput load that is outside unit-test scope.
 */
class NettyPipelineWsEchoTest {

    /**
     * Minimal WS echo handler that mirrors BenchmarkRoutingHandler's /ws-echo logic.
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
     * Single VU: 1 connection, 3 sequential text echo rounds.
     * Baseline — must pass for the 2-VU test to be meaningful.
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
            val echo1 = CompletableFuture<String>()
            val echo2 = CompletableFuture<String>()
            val echo3 = CompletableFuture<String>()
            val echoes = listOf(echo1, echo2, echo3)
            var echoIdx = 0

            // Suspend-aware (`await()`) instead of blocking (`.get(N, TimeUnit)`)
            // for the same reason as the K40 fix on the five-concurrent test:
            // a blocking `.get()` does not cooperate with the outer `runTest`
            // dispatch-timeout cancellation, so a stalled call appears as a
            // raw `TimeoutException` rather than a clean coroutine-cancel
            // stack trace. `runTest` enforces its own dispatch-timeout
            // (default 60 s) which fires if any single `await()` stalls.
            val ws = buildWsClient().newWebSocketBuilder()
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
        } finally {
            server.close()
            engine.close()
        }
    }

    /**
     * K4 regression: 2 concurrent VUs each send a text frame and wait for the echo.
     * Verifies that the WS upgrade + echo path functions correctly for multiple
     * simultaneous connections — the scenario that exposed the K4 OOM crash in the
     * benchmark. Unit-test scale (2 frames) does not exhaust the Netty pool; the
     * full-scale pool exhaustion requires sustained 50-VU benchmark load.
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
            val http = buildWsClient()

            // Suspend-aware setup (`await()` not `.get(N, TimeUnit)`) and
            // parallelised connection establishment via `coroutineScope { ...
            // async { ... }.awaitAll() }` — same K40 pattern applied to the
            // five-concurrent test. `runTest` provides the outer
            // dispatch-timeout safety net (default 60 s) so any stalled
            // `await()` surfaces as a clean coroutine-cancel rather than a
            // raw `TimeoutException`.
            suspend fun openWs(): Pair<WebSocket, CompletableFuture<String>> {
                val echoFuture = CompletableFuture<String>()
                val pending = StringBuilder()
                val ws = http.newWebSocketBuilder()
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
        } finally {
            server.close()
            engine.close()
        }
    }

    /**
     * K4 extended: 5 concurrent connections, each does 3 echo rounds.
     * Exercises the pipeline under more load to catch intermittent hangs.
     *
     * **K40 (CI flake fix)**: previously `CompletableFuture.get(N, TimeUnit.SECONDS)`
     * was used both for connection setup and echo waits. Each `.get()` is a JVM
     * blocking call on the `runBlocking` coroutine thread — it does not cooperate
     * with `withTimeout`, so the outer 60 s cap could not interrupt a stalled
     * step. Worse, sequential setup (5 connections × 15 s worst case = 75 s)
     * exceeded the cap on its own under heavy GitHub Actions runner load.
     *
     * Fixed by:
     * - `kotlinx.coroutines.future.await()` (suspend) replaces every `.get()`.
     *   `withTimeout` now correctly enforces the 60 s budget by cancelling the
     *   awaiting continuation rather than waiting for a JVM-blocking call to
     *   return on its own schedule.
     * - `async / awaitAll` parallelises connection setup so the 5 builds run
     *   concurrently instead of in sequence.
     */
    @Test
    fun `ws-echo five concurrent connections all complete multiple rounds`() = runBlocking {
        withTimeout(60.seconds) {
            val engine = NettyEngine()
            val server = engine.bindPipeline("127.0.0.1", 0) { channel ->
                channel.pipeline.addLast("encoder", HttpResponseEncoder())
                channel.pipeline.addLast("decoder", HttpRequestDecoder())
                channel.pipeline.addLast("ws-echo", WsEchoHandler())
            }
            val port = (server.localAddress as InetSocketAddress).port
            try {
                val http = buildWsClient()
                val connections = coroutineScope {
                    (1..5).map { id ->
                        async {
                            val rounds = 3
                            val futures = (1..rounds).map { CompletableFuture<String>() }
                            var roundIdx = 0
                            val pending = StringBuilder()
                            val ws = http.newWebSocketBuilder()
                                .buildAsync(URI("ws://127.0.0.1:$port/ws-echo"), object : WebSocket.Listener {
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
                                })
                                .await()
                            Triple(id, ws, futures)
                        }
                    }.awaitAll()
                }

                for ((id, ws, _) in connections) {
                    for (r in 1..3) ws.sendText("vu$id-r$r", true)
                }

                for ((id, ws, futures) in connections) {
                    for (r in 1..3) {
                        assertEquals(
                            "vu$id-r$r",
                            futures[r - 1].await(),
                            "VU$id round $r echo mismatch",
                        )
                    }
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "").await()
                }
            } finally {
                server.close()
                engine.close()
            }
        }
    }

    private fun buildWsClient(): HttpClient = HttpClient.newHttpClient()
}
