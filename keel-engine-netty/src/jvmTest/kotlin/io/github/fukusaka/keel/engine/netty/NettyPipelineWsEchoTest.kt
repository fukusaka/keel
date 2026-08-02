package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.codec.http.HttpRequestDecoder
import io.github.fukusaka.keel.codec.http.HttpResponseEncoder
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.testing.http.newTestHttpClient
import io.github.fukusaka.keel.testing.websocket.WsEchoHandler
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.test.runTest
import java.net.URI
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Integration smoke test for the pipeline-http-netty WebSocket upgrade + echo path.
 *
 * Verifies that real Netty engine accept + JDK HttpClient WebSocket roundtrip works
 * end-to-end for the keel pipeline. The test exercises:
 *
 * 1. HTTP→WS upgrade through `NettyEngine.bindPipeline` with `HttpRequestDecoder` →
 *    `HttpResponseEncoder` → [WsEchoHandler], and the in-place pipeline mutation
 *    (HTTP codec removed, `WsFrameDecoder` / `WsFrameEncoder` inserted) on upgrade.
 * 2. Multi-connection state isolation (the `two concurrent` test) — two channels
 *    must not cross-talk.
 *
 * **IoBuf-leak regression coverage moved to [NettyPipelineWsEchoSeamTest]**:
 * the original `five concurrent connections all complete multiple rounds` test was
 * an indirect leak indicator (5 conn × 3 round = 15 frames was several orders of
 * magnitude below the 50-VU sustained 60 s scale required to actually trigger the
 * SIGKILL the bug originally produced). The seam test detects the leak directly through
 * [io.github.fukusaka.keel.buf.TrackingAllocator] alloc/release count comparison
 * across 1000+ frames per channel, with deterministic interleaved-multi-channel
 * scenarios that the integration test could not produce. Sustained-load OOM at
 * proper scale is covered by `NettyPipelineWsStressTest` (gated by the
 * `keel.stress=true` system property).
 *
 * **Resource discipline (see [io.github.fukusaka.keel.testing.http.TestHttpClient])**:
 * every test wraps `HttpClient` use in `newTestHttpClient().use { ... }` so that the
 * JDK HTTP client and its executor
 * are torn down deterministically. Without this, fresh `HttpClient.newHttpClient()`
 * instances fork a private selector + executor pair per test that survives the
 * test method, accumulating zombie threads across the suite. On resource-
 * constrained CI runners (GHA macOS Apple Silicon in particular) the accumulated
 * threads multiply scheduler contention, causing later tests to drift from sub-
 * second runtime towards the outer test timeout.
 */
class NettyPipelineWsEchoTest {

    /**
     * Single VU: 1 connection, 3 sequential text echo rounds.
     *
     * Baseline integration smoke — verifies the wire-level WS upgrade + echo round-
     * trip works end-to-end (Netty server accept + JDK HttpClient builds the upgrade
     * request, server processes it through [WsEchoHandler], pipeline mutates,
     * subsequent text frames are echoed back). All `.get(N, TimeUnit)` blocking
     * calls use [kotlinx.coroutines.future.await] so the surrounding [runTest]
     * dispatch-timeout (default 60 s) drives cancellation if any single op stalls.
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
            newTestHttpClient().use { client ->
                val echo1 = CompletableFuture<String>()
                val echo2 = CompletableFuture<String>()
                val echo3 = CompletableFuture<String>()
                val echoes = listOf(echo1, echo2, echo3)
                var echoIdx = 0

                val ws = client.http.newWebSocketBuilder()
                    .buildAsync(
                        URI("ws://127.0.0.1:$port/ws-echo"),
                        object : WebSocket.Listener {
                            override fun onOpen(ws: WebSocket) = ws.request(Long.MAX_VALUE)
                            override fun onText(ws: WebSocket, data: CharSequence, last: Boolean) =
                                echoes.getOrNull(echoIdx++)?.complete(data.toString()).let { null }
                        },
                    )
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
     * 2 concurrent connections each send a text frame and wait for the echo.
     * Verifies that the WS upgrade + echo path functions correctly for multiple
     * simultaneous connections — the multi-channel state-isolation smoke test
     * at the real-network level. Deterministic state-isolation coverage at the
     * handler level is in [NettyPipelineWsEchoSeamTest].
     *
     * Connection setup is parallelised via
     * `coroutineScope { ... async { ... }.awaitAll() }` so the two builds run
     * concurrently and the test wall-time is bounded by the slowest connection
     * rather than the sum.
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
            newTestHttpClient().use { client ->
                suspend fun openWs(): Pair<WebSocket, CompletableFuture<String>> {
                    val echoFuture = CompletableFuture<String>()
                    val pending = StringBuilder()
                    val ws = client.http.newWebSocketBuilder()
                        .buildAsync(
                            URI("ws://127.0.0.1:$port/ws-echo"),
                            object : WebSocket.Listener {
                                override fun onOpen(ws: WebSocket) = ws.request(Long.MAX_VALUE)
                                override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): Nothing? {
                                    pending.append(data)
                                    if (last) echoFuture.complete(pending.toString())
                                    return null
                                }
                                override fun onError(ws: WebSocket, error: Throwable) {
                                    echoFuture.completeExceptionally(error)
                                }
                            },
                        )
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
}
