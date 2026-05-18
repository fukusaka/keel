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
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
