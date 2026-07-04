package io.github.fukusaka.keel.tls.awslc

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
 * Pins that [TlsConfig.verifyHostname] drives OpenSSL client hostname
 * verification (`SSL_set1_host`).
 *
 * The server certificate is self-signed with `CN=localhost` and acts as its
 * own trust anchor ([TestCertificates.SERVER_CERT]), so a client that trusts
 * it verifies the chain regardless of the name. The tests pin the hostname
 * axis on top of that:
 *
 * - default ([TlsConfig.verifyHostname] = null): a matching `serverName`
 *   completes and a mismatched one aborts — the regression pin for the
 *   previously-missing check (a mismatched name used to be silently accepted).
 * - `verifyHostname = false`: a mismatched name still completes (chain-only).
 */
class AwsLcHostnameVerificationTest {

    private val allocator: BufferAllocator = DefaultAllocator
    private val factory = AwsLcCodecFactory()
    private val pump = AwsLcHandshakePump(allocator)

    private val serverCerts = TlsCertificateSource.Pem(
        TestCertificates.SERVER_CERT,
        TestCertificates.SERVER_KEY,
    )

    private fun server() = factory.createServerCodec(
        TlsConfig(certificates = serverCerts, verifyMode = TlsVerifyMode.NONE),
    )

    private fun verifyingClient(serverName: String?, verifyHostname: Boolean? = null) =
        factory.createClientCodec(
            TlsConfig(
                trustAnchors = TlsTrustSource.Pem(TestCertificates.SERVER_CERT),
                verifyMode = TlsVerifyMode.PEER,
                serverName = serverName,
                verifyHostname = verifyHostname,
            ),
        )

    @Test
    fun `a verifying client completes when serverName matches the certificate`() {
        val server = server()
        val client = verifyingClient(serverName = "localhost")

        pump.driveHandshake(client = client, server = server)
        assertTrue(client.isHandshakeComplete, "client must complete against a trusted, name-matching server")
        assertTrue(server.isHandshakeComplete, "server must complete the handshake")

        client.close()
        server.close()
    }

    @Test
    fun `a verifying client aborts when serverName does not match the certificate`() {
        val server = server()
        val client = verifyingClient(serverName = "wrong.example.com")

        assertFailsWith<TlsException>("a name mismatch must abort the handshake") {
            pump.driveHandshake(client = client, server = server)
        }
        assertFalse(client.isHandshakeComplete, "client must not complete against a name-mismatched certificate")

        client.close()
        server.close()
    }

    @Test
    fun `verifyHostname false verifies the chain but tolerates a name mismatch`() {
        val server = server()
        val client = verifyingClient(serverName = "wrong.example.com", verifyHostname = false)

        pump.driveHandshake(client = client, server = server)
        assertTrue(client.isHandshakeComplete, "chain-only client must complete despite the name mismatch")
        assertTrue(server.isHandshakeComplete, "server must complete the handshake")

        client.close()
        server.close()
    }
}
