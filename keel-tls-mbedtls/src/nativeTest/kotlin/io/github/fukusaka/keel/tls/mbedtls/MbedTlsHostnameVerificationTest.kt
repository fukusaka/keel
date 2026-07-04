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
 * under the wrong name must still abort the handshake. A third case pins
 * `verifyHostname = false`: the chain is still verified but a name mismatch
 * is tolerated (chain-only), via the per-SSL CN-mismatch-clearing callback.
 * A fourth case pins that the callback clears *only* the name mismatch — a
 * chain-only client presented with an untrusted certificate must still abort.
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

    @Test
    fun `verifyHostname false verifies the chain but tolerates a name mismatch`() {
        val server = factory.createServerCodec(
            TlsConfig(certificates = serverCerts, verifyMode = TlsVerifyMode.NONE),
        )
        val client = factory.createClientCodec(
            TlsConfig(
                trustAnchors = TlsTrustSource.Pem(TestCertificates.SERVER_CERT),
                serverName = "wrong.example.com",
                verifyHostname = false,
            ),
        )

        pump.driveHandshake(client = client, server = server)
        assertTrue(client.isHandshakeComplete, "chain-only client must complete despite the name mismatch")
        assertTrue(server.isHandshakeComplete, "server must complete the handshake")

        client.close()
        server.close()
    }

    @Test
    fun `verifyHostname false still rejects an untrusted chain`() {
        val server = factory.createServerCodec(
            TlsConfig(certificates = serverCerts, verifyMode = TlsVerifyMode.NONE),
        )
        // Chain-only, but the trust anchor is an unrelated CA — the server
        // certificate is not signed by it. The name matches, so only the
        // chain-trust flag is set; the CN-mismatch-clearing callback must not
        // touch it, so the handshake must still abort.
        val client = factory.createClientCodec(
            TlsConfig(
                trustAnchors = TlsTrustSource.Pem(TestCertificates.CLIENT_CA_CERT),
                serverName = "localhost",
                verifyHostname = false,
            ),
        )

        assertFailsWith<TlsException>("an untrusted chain must abort even in chain-only mode") {
            pump.driveHandshake(client = client, server = server)
        }
        assertFalse(client.isHandshakeComplete, "chain-only must not accept an untrusted certificate")

        client.close()
        server.close()
    }
}
