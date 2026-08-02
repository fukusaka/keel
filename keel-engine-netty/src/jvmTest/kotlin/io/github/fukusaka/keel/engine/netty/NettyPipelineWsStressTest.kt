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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Sustained-load stress test for the NettyEngine WebSocket pipeline.
 *
 * **Purpose**: catches regressions of the per-frame IoBuf leak (and any
 * sibling failure mode that only surfaces at production load — kernel
 * pressure, scheduler contention, real-network back-pressure) at the scale
 * the original SIGKILL was observed in a 50-VU 60 s benchmark. The seam
 * tests in [NettyPipelineWsEchoSeamTest] / [NettyPipelineWsLargePayloadTest]
 * detect the leak deterministically through alloc-vs-release counting at far
 * cheaper cost; this file exists for the failure classes that scale only
 * with sustained-load * timing * concurrency and for which the seam path
 * cannot reproduce.
 *
 * **Opt-in gate**: every test method calls
 * `assumeTrue(System.getProperty("keel.stress") == "true")` at entry, so
 * the whole class is a no-op for the default PR-gate test run. Opt in via:
 *
 * ```
 * ./gradlew :keel-engine-netty:jvmTest -Dkeel.stress=true
 * ```
 *
 * The dedicated CI workflow `.github/workflows/netty-ws-stress.yml` (manual
 * `workflow_dispatch` trigger) sets the property and runs the suite on a
 * `macos-15` runner — the same runner family where the original macOS-runner SIGKILL flake
 * was observed.
 *
 * **Why not `@Tag("stress")`**: kotlin-test on the JVM target uses JUnit 4
 * via `kotlin-test-junit` by default, where `@org.junit.jupiter.api.Tag` is
 * not recognised. Switching the runner to JUnit Platform / Jupiter for one
 * test class is disproportionate infrastructure churn; the system-property
 * gate is portable across JUnit versions and keeps the runtime semantics
 * (skipped at PR gate, run on demand) identical.
 *
 * **Scale per test**: each scenario keeps end-to-end runtime under 5 minutes
 * on the macOS Apple Silicon runner so the workflow's per-job budget fits
 * within standard CI timeouts. Frame counts and connection counts are
 * documented inline.
 */
class NettyPipelineWsStressTest {

    /**
     * Sustained-load: 50 concurrent WebSocket connections, each completing
     * 100 round-trip text echoes. Total: 5000 frames in flight across the
     * `NettyEngine` worker threads, exercising the same code paths the
     * original 50-VU/60 s benchmark stressed but at unit-test cost.
     *
     * The success criterion is binary — every connection completes every
     * round without exception or timeout, and the engine shuts down cleanly.
     * The underlying IoBuf leak would manifest as either OOM during the run or a
     * `BufferAllocator` leak on engine close; neither is asserted directly
     * here because the seam tests already catch the IoBuf-counting class
     * deterministically. This test's value is the *combination* of real
     * Netty accept + real JDK HttpClient + real network bytes flowing for
     * the duration.
     */
    @Test
    fun `50 concurrent connections each complete 100 echo rounds without leak or timeout`() = runBlocking {
        // withTimeout: 5 minutes budget — matches the existing perTestTimeout wall-clock check.
        // Stress test runs 50 × 100 = 5000 echo rounds over real Netty + JDK HttpClient.
        withTimeout(5.minutes) {
            assumeTrue(
                "stress tests are gated; opt in with -Dkeel.stress=true",
                System.getProperty("keel.stress") == "true",
            )

            val connections = 50
            val rounds = 100
            val perTestTimeout = 5.minutes

            val engine = NettyEngine()
            val server = engine.bindPipeline("127.0.0.1", 0) { channel ->
                channel.pipeline.addLast("encoder", HttpResponseEncoder())
                channel.pipeline.addLast("decoder", HttpRequestDecoder())
                channel.pipeline.addLast("ws-echo", WsEchoHandler())
            }
            val port = (server.localAddress as InetSocketAddress).port

            try {
                newTestHttpClient(threadPoolSize = 16).use { client ->
                    val totalEchoes = AtomicLong(0)
                    val startNanos = System.nanoTime()
                    coroutineScope {
                        (1..connections).map { vu ->
                            async { runConnection(client.http, port, vu, rounds, totalEchoes) }
                        }.awaitAll()
                    }
                    val elapsed = (System.nanoTime() - startNanos).nanoseconds
                    assertEquals(
                        (connections * rounds).toLong(),
                        totalEchoes.get(),
                        "expected $connections × $rounds = ${connections * rounds} echoes",
                    )
                    require(elapsed < perTestTimeout) {
                        "stress test wall-clock $elapsed exceeded budget $perTestTimeout"
                    }
                }
            } finally {
                server.close()
                engine.close()
            }
        }
    }

    /**
     * Drive one VU: open a WebSocket, send [rounds] sequential text frames
     * each waiting for its echo, then close. A FIFO queue of
     * [CompletableFuture] entries — one per outstanding round — bridges
     * the [WebSocket.Listener] callbacks (running on the test executor)
     * back to this coroutine: the sender enqueues a future, then suspends
     * on `kotlinx.coroutines.future.await`; the listener completes the
     * head-of-queue future when an echo arrives, which resumes the sender
     * on its original coroutine dispatcher. Access to the shared queue is
     * `synchronized` because the listener thread and the coroutine thread
     * mutate it concurrently.
     */
    private suspend fun runConnection(
        http: HttpClient,
        port: Int,
        vu: Int,
        rounds: Int,
        totalEchoes: AtomicLong,
    ) {
        val pending = ArrayDeque<CompletableFuture<String>>()
        val ws = http.newWebSocketBuilder()
            .buildAsync(
                URI("ws://127.0.0.1:$port/ws-echo"),
                object : WebSocket.Listener {
                    override fun onOpen(ws: WebSocket) = ws.request(Long.MAX_VALUE)
                    override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): Nothing? {
                        if (last) {
                            synchronized(pending) {
                                pending.removeFirstOrNull()?.complete(data.toString())
                            }
                        }
                        return null
                    }
                    override fun onError(ws: WebSocket, error: Throwable) {
                        synchronized(pending) {
                            while (pending.isNotEmpty()) {
                                pending.removeFirst().completeExceptionally(error)
                            }
                        }
                    }
                },
            )
            .await()

        try {
            for (round in 1..rounds) {
                val payload = "vu$vu-r$round"
                val echo = CompletableFuture<String>()
                synchronized(pending) { pending.addLast(echo) }
                ws.sendText(payload, true).await()
                val received = echo.await()
                assertEquals(payload, received, "vu=$vu round=$round mismatch")
                totalEchoes.incrementAndGet()
            }
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "").await()
        } catch (t: Throwable) {
            ws.abort()
            throw t
        }
    }
}
