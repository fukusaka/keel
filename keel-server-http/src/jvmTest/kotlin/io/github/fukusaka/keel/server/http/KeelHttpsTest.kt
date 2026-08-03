package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.engine.netty.NettyEngine
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.server.ServerTlsStrategy
import io.github.fukusaka.keel.server.http.dsl.keelHttpServer
import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsVerifyMode
import io.github.fukusaka.keel.tls.jsse.JsseTlsCodecFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Real-engine HTTPS integration tests for the connector layer.
 *
 * Drive `keelHttpServer { connector { tls { } } }` end to end, one test
 * per `ServerTlsStrategy`:
 *
 * - `KeelCodec` on [NioEngine] — `ServerConnector.resolveBindConfig`
 *   yields a `TlsServerConfig` installing keel's `TlsHandler` per
 *   connection. `KeelCodec` works on every engine.
 * - `EngineNative` on [NettyEngine] — resolution delegates to
 *   `NettyEngine.nativeTlsBindConfig`, which terminates TLS with Netty's
 *   native `SslHandler`.
 */
class KeelHttpsTest {

    private val tlsConfig = TlsConfig(
        certificates = TlsCertificateSource.Pem(
            TestCertificates.SERVER_CERT,
            TestCertificates.SERVER_KEY,
        ),
        verifyMode = TlsVerifyMode.NONE,
    )

    @Test
    fun `keelHttpServer with a TLS connector serves HTTPS over the KeelCodec strategy`() = runBlocking {
        // Budget covers TLS handshake + a loopback request round-trip.
        withTimeout(15.seconds) {
            val engine = NioEngine()
            val factory = JsseTlsCodecFactory()
            val server = keelHttpServer(engine) {
                connector {
                    host = "127.0.0.1"
                    port = 0
                    tls {
                        config = tlsConfig
                        strategy = ServerTlsStrategy.KeelCodec(factory)
                    }
                }
                get("/hello") { call -> call.respondText("secure hello") }
            }
            server.start()
            val port = (server.localAddress as InetSocketAddress).port
            try {
                val (status, body) = httpsGet(port, "/hello")
                assertEquals(200, status)
                assertEquals("secure hello", body)
            } finally {
                server.stop()
                factory.close()
                engine.close()
            }
        }
    }

    @Test
    fun `keelHttpServer with a TLS connector serves HTTPS over the EngineNative strategy`() = runBlocking {
        // Budget covers TLS handshake + a loopback request round-trip.
        withTimeout(15.seconds) {
            val engine = NettyEngine()
            val server = keelHttpServer(engine) {
                connector {
                    host = "127.0.0.1"
                    port = 0
                    tls {
                        config = tlsConfig
                        strategy = ServerTlsStrategy.EngineNative
                    }
                }
                get("/hello") { call -> call.respondText("secure hello") }
            }
            server.start()
            val port = (server.localAddress as InetSocketAddress).port
            try {
                val (status, body) = httpsGet(port, "/hello")
                assertEquals(200, status)
                assertEquals("secure hello", body)
            } finally {
                server.stop()
                engine.close()
            }
        }
    }

    @Test
    fun `a TLS handshake that starts but never completes is force-closed`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = NioEngine()
            val factory = JsseTlsCodecFactory()
            val server = keelHttpServer(engine) {
                connector {
                    host = "127.0.0.1"
                    port = 0
                    tls {
                        config = tlsConfig.copy(handshakeTimeoutMillis = HANDSHAKE_TIMEOUT_MS)
                        strategy = ServerTlsStrategy.KeelCodec(factory)
                    }
                }
                get("/hello") { call -> call.respondText("secure hello") }
            }
            server.start()
            val port = (server.localAddress as InetSocketAddress).port
            val client = Socket(InetAddress.getLoopbackAddress(), port)
            client.soTimeout = 5_000
            try {
                // Send the first few bytes of a TLS record (handshake content type +
                // version) then stall — enough for the server to begin the handshake
                // and arm the deadline, but never enough to complete it. The absolute
                // handshake deadline must force-close once the budget elapses.
                client.getOutputStream().apply {
                    write(byteArrayOf(0x16, 0x03, 0x01))
                    flush()
                }
                val startMs = System.currentTimeMillis()
                assertTrue(
                    readUntilClosed(client),
                    "the server must force-close a peer whose TLS handshake never completes",
                )
                val elapsedMs = System.currentTimeMillis() - startMs
                // Confirm the close was the deadline firing, not an immediate TLS
                // parse error on the partial record: it must take roughly the budget.
                assertTrue(
                    elapsedMs >= HANDSHAKE_TIMEOUT_MS / 2,
                    "close came after ${elapsedMs}ms — too early to be the handshake deadline (${HANDSHAKE_TIMEOUT_MS}ms)",
                )
            } finally {
                client.close()
                server.stop()
                factory.close()
                engine.close()
            }
        }
    }

    /** Drains until EOF (-1) or a reset, bounded so a non-closing bug fails rather than hangs. */
    private fun readUntilClosed(client: Socket): Boolean {
        val ins = client.getInputStream()
        val buf = ByteArray(1024)
        var reads = 0
        while (reads < MAX_READS) {
            reads++
            try {
                if (ins.read(buf) == -1) return true
            } catch (_: SocketTimeoutException) {
                return false
            } catch (_: java.io.IOException) {
                return true // connection reset = closed
            }
        }
        return false
    }

    /** Performs an HTTPS GET trusting any server certificate (self-signed test cert). */
    private fun httpsGet(port: Int, path: String): Pair<Int, String> {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(TrustAllCertificates), SecureRandom())
        val connection = URI("https://127.0.0.1:$port$path").toURL().openConnection() as HttpsURLConnection
        connection.sslSocketFactory = context.socketFactory
        connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        return connection.responseCode to connection.inputStream.readBytes().decodeToString()
    }

    /** Trust manager that accepts any certificate — test client only. */
    private object TrustAllCertificates : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 500L
        const val MAX_READS = 200
    }
}
