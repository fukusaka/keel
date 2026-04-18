package io.github.fukusaka.keel.tls.jsse

import io.github.fukusaka.keel.core.InetSocketAddress

import io.github.fukusaka.keel.codec.http.HttpRequestDecoder
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseEncoder
import io.github.fukusaka.keel.codec.http.RoutingHandler
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsConnectorConfig
import io.github.fukusaka.keel.tls.TlsVerifyMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test: NIO Pipeline + TlsHandler(JSSE) + HTTP codec.
 *
 * Starts an HTTPS server using:
 * - [NioEngine] with Pipeline mode
 * - [TlsHandler] with [JsseTlsCodec] for TLS record protection
 * - HTTP request/response codec + routing handler
 *
 * Validates end-to-end: curl -> HTTPS -> TLS decrypt -> HTTP decode ->
 * route -> HTTP encode -> TLS encrypt -> response to curl.
 */
class JsseHttpsEchoTest {

    private val tlsConfig = TlsConfig(
        certificates = TlsCertificateSource.Pem(
            TestCertificates.SERVER_CERT,
            TestCertificates.SERVER_KEY,
        ),
        verifyMode = TlsVerifyMode.NONE,
    )

    @Test
    fun `HTTPS echo via curl`() {
        val factory = JsseTlsCodecFactory()
        val engine = NioEngine()

        val response = HttpResponse.ok("Hello, JSSE!", contentType = "text/plain")

        val server = engine.bindPipeline("127.0.0.1", 0, config = TlsConnectorConfig(tlsConfig, factory)) { channel ->
            channel.pipeline.addLast("encoder", HttpResponseEncoder())
            channel.pipeline.addLast("decoder", HttpRequestDecoder())
            channel.pipeline.addLast("routing", RoutingHandler(mapOf("/hello" to { response })))
        }
        val port = (server.localAddress as InetSocketAddress).port

        // Allow server thread to start accepting connections.
        Thread.sleep(SERVER_START_DELAY_MS)

        val (exitCode, output) = curlHttps(port, "/hello")

        // Cleanup
        server.close()
        factory.close()
        engine.close()

        // Verify curl succeeded and response is correct.
        assertEquals(0, exitCode, "curl exit code")
        val lines = output.trimEnd().lines()
        assertTrue(lines.size >= 2, "expected body + status code, got: $output")
        assertEquals("Hello, JSSE!", lines.dropLast(1).joinToString("\n"))
        assertEquals("200", lines.last())
    }

    /**
     * Runs curl to make an HTTPS request and captures output.
     *
     * @return Pair of (exit code, stdout output).
     */
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
        private const val SERVER_START_DELAY_MS = 200L
        private const val CURL_MAX_TIME_SECS = "5"
        private const val CURL_CONNECT_TIMEOUT_SECS = "3"
    }
}
