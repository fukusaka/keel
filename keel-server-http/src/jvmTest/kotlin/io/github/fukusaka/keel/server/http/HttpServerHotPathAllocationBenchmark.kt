package io.github.fukusaka.keel.server.http

import com.sun.management.ThreadMXBean
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseHead
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.lang.management.ManagementFactory
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Measures per-request JVM allocation for the keel-server-http hot path.
 *
 * Drives `installHttpServerPipeline` (HTTP/1.1 streaming codec +
 * `HttpServerHandler`) over a [TestIoTransport] with
 * [Dispatchers.Unconfined], so each request → response round-trip
 * completes synchronously within `notifyRead` and the
 * `ThreadMXBean.getThreadAllocatedBytes` delta captures the entire
 * lifecycle's allocation (decode, route, handler launch, response
 * encode, transport write).
 *
 * Five scenarios are compared in the same JVM to keep the snapshot of
 * per-request bytes-per-allocation across the dominant request shapes.
 * They share a single [TestIoTransport] / [AbstractPipelinedChannel] /
 * [Router] across all iterations so warmup amortises Router-trie
 * construction and HTTP/1 codec state-machine init; only per-request
 * costs are measured.
 *
 * - **A (/hello GET, no body)**: minimal request — request line +
 *   `Host` only, 13-byte response. The /hello shape used by
 *   `pipeline-http-*` benches.
 * - **B (/large GET, 10 KB body)**: small request, large response —
 *   exercises the response chunked-or-fixed encode + flush.
 * - **C (POST /echo with 100-byte fixed-length body)**: request body
 *   parsing path + small response.
 * - **D (POST /echo with 10 KB fixed-length body)**: large request body
 *   parsing — amplifies any per-`HttpBody` allocation.
 * - **E (10 × /hello GET on a warm connection)**: amortises per-request
 *   alloc over a keep-alive sequence — useful to isolate the
 *   per-connection setup tail from the steady-state per-request cost.
 * - **F (/stream GET, 10 × 100B chunked)**: SSE-shaped streaming
 *   response via `respondStream` — exercises the chunked
 *   transfer-encoding emit path and `HttpResponseBodySink.write`. Each
 *   chunk allocates a fresh `IoBuf`, so this scenario surfaces the
 *   per-chunk overhead on top of the per-request baseline.
 * - **G (/stream-large GET, 100 × 1000B chunked)**: same streaming
 *   path as F at a larger chunk count and size — drives a more
 *   realistic streaming workload (NDJSON / 100 KB SSE).
 *
 * Uses `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` (same
 * primitive as [io.github.fukusaka.keel.engine.netty.NettyReadPathAllocationBenchmark]).
 * Excludes warmup; reports median of TRIALS for each scenario.
 *
 * Not a unit test — runs as a `@Test` so it executes under the normal
 * `jvmTest` task; inspect stdout for the numbers. Does not assert.
 *
 * Pair with `:keel-engine-netty:NettyReadPathAllocationBenchmark` (engine
 * layer per-receive alloc) for full-stack alloc accounting. The
 * keel-server-http hot path targets sub-millisecond latency at 1 M+
 * rps, so per-request alloc has to stay bounded — this benchmark is
 * the measurement gate against which future PRs touching the hot path
 * can A/B.
 */
// @Ignore: one-time measurement (no functional assertion) — a decision
// aid that caught no regression, so it is not run in the gate / CI; kept
// for re-verification. The verified content + conclusion is the class
// KDoc above.
// Re-run: remove @Ignore, then
//   ./gradlew :keel-server-http:jvmTest --tests "*HttpServerHotPathAllocationBenchmark"
@Ignore
class HttpServerHotPathAllocationBenchmark {

    private val tmx = ManagementFactory.getThreadMXBean() as ThreadMXBean

    private fun measure(iterations: Int, scenario: Scenario): Long {
        // Per-scenario warmup walks the same path the measurement uses
        // so the first measured iteration is steady-state (Router trie
        // already traversed, codec state machine settled).
        repeat(WARMUP) { scenario.runOnce() }

        val start = tmx.getThreadAllocatedBytes(Thread.currentThread().threadId())
        repeat(iterations) { scenario.runOnce() }
        val end = tmx.getThreadAllocatedBytes(Thread.currentThread().threadId())
        return (end - start) / iterations
    }

    /**
     * A scenario sets up a pipeline + router once and exposes a
     * [runOnce] that feeds one request through the round-trip,
     * discarding the response.
     */
    private abstract class Scenario {
        val transport = TestIoTransport()
        protected val channel = object : AbstractPipelinedChannel(transport, PrintLogger("bench")) {}
        protected val scope = CoroutineScope(Dispatchers.Unconfined)

        protected fun bufOf(bytes: ByteArray): IoBuf {
            val buf = DefaultAllocator.allocate(bytes.size)
            buf.writeByteArray(bytes, 0, bytes.size)
            return buf
        }

        /** Drains all written response buffers (releases them) so the next iteration starts fresh. */
        protected fun drainResponse() {
            val written = transport.written
            for (i in written.indices) {
                written[i].release()
            }
            written.clear()
        }

        abstract fun install()
        abstract fun runOnce()
    }

    private inner class HelloGet : Scenario() {
        private val request = (
            "GET /hello HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n"
            ).encodeToByteArray()

        override fun install() {
            channel.installHttpServerPipeline(
                Router().apply {
                    register(HttpMethod.GET, "/hello") { call ->
                        call.respond(HttpResponse.ok("Hello, World!"))
                    }
                },
                emptyList(),
                ErrorHandlers.DEFAULT,
                QueryParameterConfig.DEFAULT,
                scope,
            )
        }

        override fun runOnce() {
            channel.pipeline.notifyRead(bufOf(request))
            drainResponse()
        }
    }

    private inner class LargeGet : Scenario() {
        private val request = (
            "GET /large HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n"
            ).encodeToByteArray()
        private val largeBody = ByteArray(LARGE_BODY_SIZE) { (it and 0xFF).toByte() }

        override fun install() {
            channel.installHttpServerPipeline(
                Router().apply {
                    register(HttpMethod.GET, "/large") { call ->
                        call.respond(HttpResponse.ok(largeBody))
                    }
                },
                emptyList(),
                ErrorHandlers.DEFAULT,
                QueryParameterConfig.DEFAULT,
                scope,
            )
        }

        override fun runOnce() {
            channel.pipeline.notifyRead(bufOf(request))
            drainResponse()
        }
    }

    private inner class PostEcho(private val bodySize: Int) : Scenario() {
        private val body = ByteArray(bodySize) { 0x61 } // 'a' filler
        private val request: ByteArray = run {
            val head = (
                "POST /echo HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Length: $bodySize\r\n" +
                    "\r\n"
                ).encodeToByteArray()
            head + body
        }

        override fun install() {
            channel.installHttpServerPipeline(
                Router().apply {
                    register(HttpMethod.POST, "/echo") { call ->
                        // Drain body without retaining; response is small.
                        call.respond(HttpResponse.ok("ok"))
                    }
                },
                emptyList(),
                ErrorHandlers.DEFAULT,
                QueryParameterConfig.DEFAULT,
                scope,
            )
        }

        override fun runOnce() {
            channel.pipeline.notifyRead(bufOf(request))
            drainResponse()
        }
    }

    private inner class HelloGetCdnHeaders : Scenario() {
        // /hello with a CDN-realistic 23-header set (the shape used by
        // HttpRequestParseAllocBenchmark) — the case where header storage
        // dominates the per-request parse cost, unlike the N=1 HelloGet.
        private val request = buildString {
            append("GET /hello HTTP/1.1\r\n")
            append("Host: api.example.com\r\n")
            append("User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X) AppleWebKit/605.1.15\r\n")
            append("Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8\r\n")
            append("Accept-Language: en-US,en;q=0.9\r\n")
            append("Accept-Encoding: gzip, deflate, br\r\n")
            append("Connection: keep-alive\r\n")
            append("Cookie: session=abc123; tracking=xyz789; consent=accepted; ab_variant=B\r\n")
            append("Upgrade-Insecure-Requests: 1\r\n")
            append("Sec-Fetch-Dest: document\r\n")
            append("Sec-Fetch-Mode: navigate\r\n")
            append("CF-Connecting-IP: 203.0.113.42\r\n")
            append("CF-IPCountry: US\r\n")
            append("CF-Ray: abc123def456-DFW\r\n")
            append("CF-Visitor: {\"scheme\":\"https\"}\r\n")
            append("X-Forwarded-For: 203.0.113.42, 172.16.0.1\r\n")
            append("X-Forwarded-Proto: https\r\n")
            append("X-Real-IP: 203.0.113.42\r\n")
            append("traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01\r\n")
            append("tracestate: rojo=00f067aa0ba902b7,congo=t61rcWkgMzE\r\n")
            append("X-Request-ID: 550e8400-e29b-41d4-a716-446655440000\r\n")
            append("Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig\r\n")
            append("CDN-Loop: cloudflare; subreqs=1\r\n")
            append("\r\n")
        }.encodeToByteArray()

        override fun install() {
            channel.installHttpServerPipeline(
                Router().apply {
                    register(HttpMethod.GET, "/hello") { call ->
                        call.respond(HttpResponse.ok("Hello, World!"))
                    }
                },
                emptyList(),
                ErrorHandlers.DEFAULT,
                QueryParameterConfig.DEFAULT,
                scope,
            )
        }

        override fun runOnce() {
            channel.pipeline.notifyRead(bufOf(request))
            drainResponse()
        }
    }

    private inner class HelloGetPipelined(private val perBatch: Int) : Scenario() {
        // A single buffer carrying `perBatch` GET /hello requests
        // back-to-back, simulating HTTP/1.1 keep-alive pipelining or a
        // batched read. Measured allocation is the total across all
        // requests in the batch, so caller divides by perBatch.
        private val request: ByteArray = run {
            val one = (
                "GET /hello HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n"
                ).encodeToByteArray()
            ByteArray(one.size * perBatch).also { dst ->
                for (i in 0 until perBatch) {
                    one.copyInto(dst, i * one.size)
                }
            }
        }

        override fun install() {
            channel.installHttpServerPipeline(
                Router().apply {
                    register(HttpMethod.GET, "/hello") { call ->
                        call.respond(HttpResponse.ok("Hello, World!"))
                    }
                },
                emptyList(),
                ErrorHandlers.DEFAULT,
                QueryParameterConfig.DEFAULT,
                scope,
            )
        }

        override fun runOnce() {
            channel.pipeline.notifyRead(bufOf(request))
            drainResponse()
        }
    }

    private inner class ChunkedStream(
        private val chunkCount: Int,
        private val chunkSize: Int,
    ) : Scenario() {
        private val request = (
            "GET /stream HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n"
            ).encodeToByteArray()
        private val chunkPayload = ByteArray(chunkSize) { 0x78 } // 'x' filler

        override fun install() {
            channel.installHttpServerPipeline(
                Router().apply {
                    register(HttpMethod.GET, "/stream") { call ->
                        call.respondStream(
                            HttpResponseHead(
                                status = HttpStatus.OK,
                                headers = HttpHeaders.of(HttpHeaderName.TRANSFER_ENCODING to "chunked"),
                            ),
                        ) { sink ->
                            repeat(chunkCount) {
                                val chunk = DefaultAllocator.allocate(chunkSize)
                                chunk.writeByteArray(chunkPayload, 0, chunkSize)
                                sink.write(chunk)
                            }
                        }
                    }
                },
                emptyList(),
                ErrorHandlers.DEFAULT,
                QueryParameterConfig.DEFAULT,
                scope,
            )
        }

        override fun runOnce() {
            channel.pipeline.notifyRead(bufOf(request))
            drainResponse()
        }
    }

    /**
     * JFR allocation-profiling driver for scenario A (/hello, 1 header).
     * No-op unless `-Dkeel.jfr=1` is set, so the normal `jvmTest` run is
     * unaffected. Run under a JFR recording to attribute the per-request
     * allocation by class / site:
     *
     * ```
     * JAVA_TOOL_OPTIONS="-Dkeel.jfr=1 \
     *   -XX:StartFlightRecording=settings=profile,filename=/tmp/jfr-%p.jfr,dumponexit=true" \
     *   ./gradlew :keel-server-http:jvmTest --tests '*HotPath*jfr scenario A*' --rerun
     * jfr view allocation-by-class /tmp/jfr-<worker-pid>.jfr
     * ```
     */
    @Test
    fun `jfr scenario A profile`() {
        if (System.getProperty("keel.jfr") != "1") return
        val scenario = HelloGet()
        scenario.install()
        repeat(JFR_WARMUP) { scenario.runOnce() }
        repeat(JFR_ITERS) { scenario.runOnce() }
        scenario.transport.close()
    }

    @Test
    fun `per-request allocation across hot-path scenarios`() {
        val scenarios = listOf(
            "A (/hello GET, 13B body)" to HelloGet(),
            "H (/hello GET, CDN 23 headers)" to HelloGetCdnHeaders(),
            "B (/large GET, ${LARGE_BODY_SIZE}B body)" to LargeGet(),
            "C (POST /echo, 100B req body)" to PostEcho(SMALL_BODY_SIZE),
            "D (POST /echo, ${LARGE_BODY_SIZE}B req body)" to PostEcho(LARGE_BODY_SIZE),
            "E (10 × /hello GET, pipelined)" to HelloGetPipelined(BATCH),
            "F (/stream GET, 10 × 100B chunked)" to ChunkedStream(STREAM_SMALL_CHUNK_COUNT, SMALL_BODY_SIZE),
            "G (/stream GET, 100 × 1000B chunked)" to ChunkedStream(STREAM_LARGE_CHUNK_COUNT, MEDIUM_CHUNK_SIZE),
        )

        // Each scenario gets a fresh pipeline; install once per scenario.
        for ((_, scenario) in scenarios) {
            scenario.install()
        }

        try {
            println("=== HttpServerHotPath allocation (bytes / request, iters=$ITERS × $TRIALS trials) ===")
            for ((name, scenario) in scenarios) {
                val trials = LongArray(TRIALS) { measure(ITERS, scenario) }
                trials.sort()
                val median = trials[TRIALS / 2]
                // For scenario E, normalise across the batch so the
                // printed value is comparable to A on a per-request basis.
                val perRequest = if (scenario is HelloGetPipelined) median / BATCH else median
                val suffix = if (scenario is HelloGetPipelined) "  (per-request, batch=$BATCH)" else ""
                println("  $name median=$perRequest bytes  samples=${trials.toList()}$suffix")
            }
        } finally {
            // Close every scenario even when one of them throws — the
            // remaining transports / channels would otherwise leak their
            // pooled IoBufs and pipeline handlers until JVM teardown.
            for ((_, scenario) in scenarios) {
                runCatching { scenario.transport.close() }
            }
        }
    }

    companion object {
        private const val SMALL_BODY_SIZE = 100
        private const val LARGE_BODY_SIZE = 10_000
        private const val BATCH = 10
        private const val STREAM_SMALL_CHUNK_COUNT = 10
        private const val STREAM_LARGE_CHUNK_COUNT = 100
        private const val MEDIUM_CHUNK_SIZE = 1_000
        private const val WARMUP = 2_000
        private const val ITERS = 10_000
        private const val TRIALS = 5
        private const val JFR_WARMUP = 50_000
        private const val JFR_ITERS = 3_000_000
    }
}
