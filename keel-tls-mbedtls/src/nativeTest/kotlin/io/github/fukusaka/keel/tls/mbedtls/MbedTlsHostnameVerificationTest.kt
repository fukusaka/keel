package io.github.fukusaka.keel.tls.mbedtls

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsException
import io.github.fukusaka.keel.tls.TlsTrustSource
import io.github.fukusaka.keel.tls.TlsVerifyMode
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins that [TlsConfig.serverName] drives Mbed TLS hostname verification
 * (`mbedtls_ssl_set_hostname`) on client codecs.
 *
 * Mbed TLS refuses to verify a peer certificate without an expected
 * hostname, so a *verifying* client can only ever complete a handshake
 * when [TlsConfig.serverName] is wired through — the positive case here
 * is the regression pin for that wiring. The negative case pins that the
 * name is actually used for verification: a trusted certificate presented
 * under the wrong name must still abort the handshake.
 *
 * The test certificate is self-signed with `CN=localhost` and acts as its
 * own trust anchor ([TestCertificates.SERVER_CERT]).
 */
class MbedTlsHostnameVerificationTest {

    private val allocator: BufferAllocator = DefaultAllocator
    private val factory = MbedTlsCodecFactory()
    private val pump = MbedTlsHandshakePump(allocator)

    private val serverCerts = TlsCertificateSource.Pem(
        TestCertificates.SERVER_CERT,
        TestCertificates.SERVER_KEY,
    )

    private fun verifyingClientConfig(serverName: String?) = TlsConfig(
        trustAnchors = TlsTrustSource.Pem(TestCertificates.SERVER_CERT),
        serverName = serverName,
    )

    @Test
    fun `a verifying client completes the handshake when serverName matches the certificate`() {
        val server = factory.createServerCodec(
            TlsConfig(certificates = serverCerts, verifyMode = TlsVerifyMode.NONE),
        )
        val client = factory.createClientCodec(verifyingClientConfig(serverName = "localhost"))

        pump.driveHandshake(client = client, server = server)
        assertTrue(client.isHandshakeComplete, "client must complete against a trusted, name-matching server")
        assertTrue(server.isHandshakeComplete, "server must complete the handshake")

        client.close()
        server.close()
    }

    @Test
    fun `a verifying client aborts the handshake when serverName does not match the certificate`() {
        val server = factory.createServerCodec(
            TlsConfig(certificates = serverCerts, verifyMode = TlsVerifyMode.NONE),
        )
        val client = factory.createClientCodec(verifyingClientConfig(serverName = "wrong.example.com"))

        assertFailsWith<TlsException>("a name mismatch must abort the handshake") {
            pump.driveHandshake(client = client, server = server)
        }
        assertFalse(client.isHandshakeComplete, "client must not complete against a name-mismatched certificate")

        client.close()
        server.close()
    }
}
