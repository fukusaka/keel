package io.github.fukusaka.keel.tls.jsse

import io.github.fukusaka.keel.codec.http.HttpRequestDecoder
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseEncoder
import io.github.fukusaka.keel.codec.http.RoutingHandler
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.server.TlsCodecServerInstaller
import io.github.fukusaka.keel.server.TlsServerConfig
import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsVerifyMode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for bench infra no.8: pipeline-http-nio + JSSE — 0.12 RPS.
 *
 * Before the fix, multiple sequential HTTPS requests over the same connection
 * stalled near-indefinitely (< 0.2 RPS), caused by a partial-write or
 * selector wake-up bug in the NIO + JSSE path.
 *
 * Red-Green verified: this test FAILS (timeout) before the fix and PASSES after.
 */
class JssePipelineHttpsMultiRequestTest {

    private val tlsConfig = TlsConfig(
        certificates = TlsCertificateSource.Pem(
            TestCertificates.SERVER_CERT,
            TestCertificates.SERVER_KEY,
        ),
        verifyMode = TlsVerifyMode.NONE,
    )

    /**
     * Multiple sequential HTTPS requests over pipeline-http-nio + JSSE.
     *
     * Each request must complete within [CURL_MAX_TIME_SECS]. Before the fix,
     * requests after the first stalled (0.12 RPS ≈ 1 request per 8 seconds),
     * causing individual curl calls to time out.
     */
    @Test
    fun `pipeline HTTPS NIO multiple sequential requests all complete quickly`() = runBlocking {
        val factory = JsseTlsCodecFactory()
        val engine = NioEngine()
        val response = HttpResponse.ok("Hello, pipeline!", contentType = "text/plain")

        val server = engine.bindPipeline(
            "127.0.0.1",
            0,
            config = TlsServerConfig(tlsConfig, TlsCodecServerInstaller(factory)),
        ) { channel ->
            // Install HTTP codec in correct order: decoder first so encoder
            // (DuplexHandler) receives inbound HttpRequestHead for HEAD tracking.
            channel.pipeline.addLast("decoder", HttpRequestDecoder())
            channel.pipeline.addLast("encoder", HttpResponseEncoder())
            channel.pipeline.addLast("routing", RoutingHandler(mapOf("/hello" to { response })))
        }
        val port = (server.localAddress as InetSocketAddress).port

        Thread.sleep(SERVER_START_DELAY_MS)

        try {
            repeat(REQUEST_COUNT) { i ->
                val (exitCode, output) = curlHttps(port, "/hello")
                assertEquals(0, exitCode, "curl exit code on request ${i + 1} (output: $output)")
                val lines = output.trimEnd().lines()
                assertTrue(lines.size >= 2, "expected body + status, got: $output (request ${i + 1})")
                assertEquals("200", lines.last(), "HTTP status on request ${i + 1}")
                assertEquals("Hello, pipeline!", lines.dropLast(1).joinToString("\n"))
            }
        } finally {
            server.close()
            factory.close()
            engine.close()
        }
    }

    /**
     * Pipeline HTTPS NIO large response (100 KB).
     *
     * Verifies that the TLS protect loop correctly encodes a multi-record
     * response without truncation — companion regression to no.6.
     */
    @Test
    fun `pipeline HTTPS NIO large response completes without truncation`() = runBlocking {
        val largeBody = "x".repeat(LARGE_PAYLOAD_BYTES)
        val factory = JsseTlsCodecFactory()
        val engine = NioEngine()
        val response = HttpResponse.ok(largeBody, contentType = "text/plain")

        val server = engine.bindPipeline(
            "127.0.0.1",
            0,
            config = TlsServerConfig(tlsConfig, TlsCodecServerInstaller(factory)),
        ) { channel ->
            channel.pipeline.addLast("decoder", HttpRequestDecoder())
            channel.pipeline.addLast("encoder", HttpResponseEncoder())
            channel.pipeline.addLast("routing", RoutingHandler(mapOf("/large" to { response })))
        }
        val port = (server.localAddress as InetSocketAddress).port

        Thread.sleep(SERVER_START_DELAY_MS)

        try {
            val (exitCode, output) = curlHttps(port, "/large")
            assertEquals(0, exitCode, "curl exit code (output length: ${output.length})")
            val lines = output.trimEnd().lines()
            assertTrue(lines.size >= 2, "expected body + status code, got output of ${output.length} chars")
            assertEquals("200", lines.last(), "expected HTTP 200")
            val body = lines.dropLast(1).joinToString("\n")
            assertEquals(LARGE_PAYLOAD_BYTES, body.length, "body length mismatch: got ${body.length}")
            assertEquals(largeBody, body)
        } finally {
            server.close()
            factory.close()
            engine.close()
        }
    }

    private fun curlHttps(port: Int, path: String): Pair<Int, String> {
        val pb = ProcessBuilder(
            "curl", "-k", "-s",
            "--max-time", CURL_MAX_TIME_SECS,
            "--connect-timeout", CURL_CONNECT_TIMEOUT_SECS,
            "-w", "\n%{http_code}",
            "https://localhost:$port$path",
        )
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val output = proc.inputStream.readAllBytes().decodeToString()
        proc.waitFor()
        return proc.exitValue() to output
    }

    companion object {
        private const val REQUEST_COUNT = 5
        private const val LARGE_PAYLOAD_BYTES = 100_000
        private const val SERVER_START_DELAY_MS = 200L
        private const val CURL_MAX_TIME_SECS = "5"
        private const val CURL_CONNECT_TIMEOUT_SECS = "3"
    }
}
