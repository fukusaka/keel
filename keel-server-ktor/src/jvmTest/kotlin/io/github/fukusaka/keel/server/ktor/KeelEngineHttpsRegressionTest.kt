package io.github.fukusaka.keel.server.ktor

import io.github.fukusaka.keel.engine.netty.NettyEngine
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.server.TlsCodecServerInstaller
import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsVerifyMode
import io.github.fukusaka.keel.tls.jsse.JsseTlsCodecFactory
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Regression tests for bench infra JSSE TLS path failures.
 *
 * no.6: ktor-keel-nio + JSSE — conn reset / EOF mid-response on /large (100 KB)
 * no.7: ktor-keel-netty + JSSE — server start FAILED
 *
 * Red-Green verified: these tests FAIL before the fix and PASS after.
 */
class KeelEngineHttpsRegressionTest {

    private val tlsConfig = TlsConfig(
        certificates = TlsCertificateSource.Pem(SERVER_CERT, SERVER_KEY),
        verifyMode = TlsVerifyMode.NONE,
    )

    /**
     * Regression for bench infra no.6: NIO + JSSE + large response.
     *
     * /large returns 100 KB of payload. Before the fix, the response was
     * truncated mid-stream (conn reset / EOF) because the TLS protect loop
     * did not correctly advance across multiple 16 KB TLS records.
     */
    @Test
    fun `NIO HTTPS large response completes without truncation`() {
        val largeBody = "x".repeat(LARGE_PAYLOAD_BYTES)
        val factory = JsseTlsCodecFactory()

        val server = embeddedServer(Keel, configure = {
            engine = NioEngine()
            sslConnector(tlsConfig, TlsCodecServerInstaller(factory)) {
                host = "127.0.0.1"
                port = 0
            }
        }) {
            routing {
                get("/large") { call.respondText(largeBody) }
            }
        }
        server.start(wait = false)

        try {
            val port = runBlocking {
                withTimeout(15.seconds) {
                    server.engine.resolvedConnectors().first().port
                }
            }
            val (exitCode, output) = curlHttps(port, "/large")

            assertEquals(0, exitCode, "curl exit code (output length: ${output.length})")
            val lines = output.trimEnd().lines()
            assertTrue(lines.size >= 2, "expected body + status code, got output of ${output.length} chars")
            assertEquals("200", lines.last(), "expected HTTP 200 (output: ${output.take(200)})")
            val body = lines.dropLast(1).joinToString("\n")
            assertEquals(
                largeBody.length,
                body.length,
                "body length mismatch: got ${body.length}, want $LARGE_PAYLOAD_BYTES",
            )
            assertEquals(largeBody, body, "body content mismatch")
        } finally {
            factory.close()
            server.stop(500, 1000)
        }
    }

    /**
     * Regression for bench infra no.7: Netty + JSSE server startup and
     * first request.
     *
     * Before the fix, the bench reported "FAILED" because the server either
     * crashed on startup or could not complete TLS handshake for the first
     * request.
     */
    @Test
    fun `Netty HTTPS server starts and responds to first request`() {
        val factory = JsseTlsCodecFactory()

        val server = embeddedServer(Keel, configure = {
            engine = NettyEngine()
            sslConnector(tlsConfig, TlsCodecServerInstaller(factory)) {
                host = "127.0.0.1"
                port = 0
            }
        }) {
            routing {
                get("/hello") { call.respondText("Hello, Netty HTTPS!") }
            }
        }
        server.start(wait = false)

        try {
            val port = runBlocking {
                withTimeout(15.seconds) {
                    server.engine.resolvedConnectors().first().port
                }
            }
            val (exitCode, output) = curlHttps(port, "/hello")

            assertEquals(0, exitCode, "curl exit code (output: $output)")
            val lines = output.trimEnd().lines()
            assertTrue(lines.size >= 2, "expected body + status code, got: $output")
            assertEquals("Hello, Netty HTTPS!", lines.dropLast(1).joinToString("\n"))
            assertEquals("200", lines.last())
        } finally {
            factory.close()
            server.stop(500, 1000)
        }
    }

    /**
     * Regression for bench infra no.7: Netty + JSSE keep-alive.
     *
     * Exercises multiple sequential requests on the same HTTPS connection
     * to verify that TLS state is maintained correctly across requests.
     */
    @Test
    fun `Netty HTTPS keep-alive multiple sequential requests`() {
        val factory = JsseTlsCodecFactory()

        val server = embeddedServer(Keel, configure = {
            engine = NettyEngine()
            sslConnector(tlsConfig, TlsCodecServerInstaller(factory)) {
                host = "127.0.0.1"
                port = 0
            }
        }) {
            routing {
                get("/hello") { call.respondText("Hello, Netty HTTPS!") }
            }
        }
        server.start(wait = false)

        try {
            val port = runBlocking {
                withTimeout(15.seconds) {
                    server.engine.resolvedConnectors().first().port
                }
            }
            repeat(KEEPALIVE_REQUEST_COUNT) { i ->
                val (exitCode, output) = curlHttps(port, "/hello")
                assertEquals(0, exitCode, "curl exit code on request ${i + 1} (output: $output)")
                val lines = output.trimEnd().lines()
                assertEquals("200", lines.last(), "HTTP status on request ${i + 1}")
                assertEquals("Hello, Netty HTTPS!", lines.dropLast(1).joinToString("\n"))
            }
        } finally {
            factory.close()
            server.stop(500, 1000)
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
        private const val LARGE_PAYLOAD_BYTES = 100_000
        private const val KEEPALIVE_REQUEST_COUNT = 5
        private const val CURL_MAX_TIME_SECS = "10"
        private const val CURL_CONNECT_TIMEOUT_SECS = "5"

        private val SERVER_CERT = """
-----BEGIN CERTIFICATE-----
MIIDCTCCAfGgAwIBAgIUaVO1WKzG9gPzYk5Td3h5tNjDl0QwDQYJKoZIhvcNAQEL
BQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MB4XDTI2MDQwMzA0MjcxNloXDTI3MDQw
MzA0MjcxNlowFDESMBAGA1UEAwwJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEF
AAOCAQ8AMIIBCgKCAQEAshZok7kN0FOmy+QXXPDq4ZI0Dj/f20KYjxku2HdEcMXQ
boyY+Yh4F0Ag3YdQCa9SNwSERXKaxzQCR2FDvxR1tkx7/UFewijuvQmSLt9oqD9M
oI6+mZlwK9StE4MbuLigLoI6MGhRCzAC56ZzhH49cbS1ax4waQGaVh7/ijSz/apo
KCmoHKn1X7AuZJepnjDGwsPI0TX2m6SFAtNanH9M4Wp3uzgvlCFd7FGbwMBj+JuU
YA5cvAy/RgUPTSKjzmSAl6MN9/Uoda4qzJl0fCaZGhGxsVb9txVRCu7YTIz7MIcB
BwyphJtA0CSGa8oTJMGtUqlawGFwyOIIGJjx+CneCQIDAQABo1MwUTAdBgNVHQ4E
FgQU3Kkr9odzVo91JZso0zBsTicdW0cwHwYDVR0jBBgwFoAU3Kkr9odzVo91JZso
0zBsTicdW0cwDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAF01R
PJIlhyBh1DgS7JUbQkrhHYHvA/c25OMIQSJ8ClNJHL6yV6lrm8VIxAmPAFoNk7DX
clB3+xiZcUV0Ia1tuOgOnsouJaHQjAWdNcfweHu0mLnxRrBF/OKRDRfasN/XGrEY
xA2XszM9gkm2JrLeSt7GSfhzSykUFXDlGTiA4hExB/gCQN5Hhfkw4HXtiwsrqJTI
dA0v0c6TRwAZKuG5BIzAh9r94fM0NzYvaYamE+/WIm6orpjzUELVKjVebvmAWkN0
DckJ9HFnEw1KPYC/9e7a1JUrkfMgCFcgIdRGQA/qMHISUzQND9Zs/ZnPvhaf+x7N
wIy8X6kST+S43rMGiQ==
-----END CERTIFICATE-----
        """.trimIndent() + "\n"

        private val SERVER_KEY = """
-----BEGIN PRIVATE KEY-----
MIIEuwIBADANBgkqhkiG9w0BAQEFAASCBKUwggShAgEAAoIBAQCyFmiTuQ3QU6bL
5Bdc8OrhkjQOP9/bQpiPGS7Yd0RwxdBujJj5iHgXQCDdh1AJr1I3BIRFcprHNAJH
YUO/FHW2THv9QV7CKO69CZIu32ioP0ygjr6ZmXAr1K0Tgxu4uKAugjowaFELMALn
pnOEfj1xtLVrHjBpAZpWHv+KNLP9qmgoKagcqfVfsC5kl6meMMbCw8jRNfabpIUC
01qcf0zhane7OC+UIV3sUZvAwGP4m5RgDly8DL9GBQ9NIqPOZICXow339Sh1rirM
mXR8JpkaEbGxVv23FVEK7thMjPswhwEHDKmEm0DQJIZryhMkwa1SqVrAYXDI4ggY
mPH4Kd4JAgMBAAECggEAB6IQP2yqG+jJ+GlBWxl0Z9C1bHruZF55XYDN0jdidpbz
9RkPoXpo804rWnNnSdL66iLGbJeZ7Lnc8yRHHBSLaxHiKpu3rQjGGtIjMuEegj+c
UDFuF/VMqoRGGtT+xi8bpoKsbdC60IjxRu6Kev5SMeJ1+C5mEsofzFstxsW1hUTL
NvPt+RbuosMGk/uDKFMXYFxKmly6Tr2EMxMTMmtIdb2jCCDpVnXPCUyW2pv6PHu2
tbqQF/UExA1Bz6t6mIxIZieNckWbOcdH+UJyTss0//kRjUMrOg3Xu6pMtDbh679f
2Xoc+mhNkMIvcYS2AJ2713Ok5RmfLNOnj/PAhjYBJQKBgQDh2awW98zzb/FTZ3bl
lH2x/bdsiNzKGZvwxMUa3Id53f1rwHBFvw05cPsaiaaegfkhRFMJUAacTeMIUm7c
K4AZ8iJ0CxD70nzCmOoihZB7keZapNjYIGYLhlQGB5BczKfiL+rIgn5X03kvdL0G
K7uQ8tqwJZzqMWEUCIPNN8S0ewKBgQDJ3Hd3tyUHnWPHqtMDqllW+/E0lDvzDMIL
qti6SENjAWmDY4A9AVt02oSDqGXK47p96cO5/klULRSkjzoW6/54eB8ICIAnObPv
lIYTPXFoEICBCDweu63shfgE/DUE85DH0cI8dgMHsa/4Hq5QM3dCc7jDJLMvVYl8
ErJmdrWaSwJ/bkbawFw+tp7yNsdORss6lK5N4bDyHbxjaCysEXGctOSv2O0d5PBk
hKel9E9CDCNqgdPat7FbiPZ+5JFbkCWtZv3T1NWSdWNRh7Min7iX075pu9jCCMXJ
DdeJL2iCFM3ZK5g6C62sAzY+9e0KXvj7nMr3/Qpgk/mIbT+7G3kfkwKBgDObdMOb
hBENUPw0FRyjyZFuef06RJVf1qBK/nupi+jc7I/VuWxfU3VthGFwhQ246O3V/N8p
PrARkmx73ZsMnJNKCozwN2tP2kvPCfQTSlITnfbfFxe4Xb/RhFYp8JgieQpM+z6f
4ShvahCiL2h7r+rCUSM618CrOqoI0alWghk/AoGBALoo1MDASnYoh9b18siAYuA3
yGIdCqVeuv9SC0duPplXUVQwuYkLDZaIASA8goes6f5UiFEkE8TXYAKTitNUQqob
s0/JN9iAF2/A2ct6J46JuRo8bxt+LdZY2znb8weICRpxx7/Sf+lswHA7OiUJT8UG
XDEgg9dRd2akza/XK5Hj
-----END PRIVATE KEY-----
        """.trimIndent() + "\n"
    }
}
