package io.github.fukusaka.keel.tls.mbedtls

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsException
import io.github.fukusaka.keel.tls.TlsTrustSource
import io.github.fukusaka.keel.tls.TlsVerifyMode
import io.github.fukusaka.keel.tls.TlsVersion
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Handshake **error-path** coverage for the MbedTLS backend.
 *
 * The existing MbedTLS tests drive only the happy path (`MbedTlsEchoTest`
 * / `MbedTlsHttpsEchoTest` complete a handshake against a trusted server
 * with an external client) or config-creation failures
 * (`MbedTlsCodecTest`). Neither exercises a handshake that **fails on
 * the wire**. This test drives two real codecs against each other
 * **entirely in memory** via [TlsCodec.protect] / [unprotect] (the
 * MbedTLS codec uses an in-memory pointer-based BIO, no fd / socket) and
 * pins that a failed handshake surfaces as a structured [TlsException]
 * rather than a hang.
 *
 * **Failure vehicles**: the first test's client has no `trustAnchors` and
 * no [TlsConfig.serverName], so verification aborts (Mbed TLS refuses to
 * verify without an expected hostname; with `serverName` wired it would
 * next fail on the untrusted self-signed chain). The completing-handshake
 * counterparts live in [MbedTlsHostnameVerificationTest] (a verifying
 * client with `trustAnchors` + matching `serverName` completes fully
 * in-memory); the mutual-TLS cases (a `REQUIRED` server rejecting a
 * cert-less client, and accepting one whose cert its `trustAnchors`
 * validate) live in [MbedTlsMutualTlsTest] paired with an OpenSSL client.
 *
 * The companion close-path bug — `protect()` leaving `send_capacity` /
 * `send_written` stale so `close()`'s `mbedtls_ssl_close_notify` writes
 * into `null + send_written` — needs a *completed* handshake to fire
 * (close_notify is not sent on an aborted one), which this test's client
 * cannot reach. Its Red-Green regression therefore lives in
 * [MbedTlsServerCloseRegressionTest], which pairs the MbedTLS server with
 * an OpenSSL client that can complete the handshake. That path mirrors
 * production, where the MbedTLS server completes a handshake, writes a
 * response via `protect`, then `close()` emits close_notify.
 */
class MbedTlsHandshakeErrorPathTest {

    private val allocator: BufferAllocator = DefaultAllocator
    private val factory = MbedTlsCodecFactory()
    private val pump = MbedTlsHandshakePump(allocator)

    private val serverCerts = TlsCertificateSource.Pem(
        TestCertificates.SERVER_CERT,
        TestCertificates.SERVER_KEY,
    )

    @Test
    fun `handshake fails with TlsException when the client cannot verify the server`() {
        val server = factory.createServerCodec(
            TlsConfig(certificates = serverCerts, verifyMode = TlsVerifyMode.NONE),
        )
        // The client has neither trustAnchors nor serverName, so the
        // default peer verification aborts the handshake — surfacing as
        // a TlsException through the pump.
        val client = factory.createClientCodec(TlsConfig())

        assertFailsWith<TlsException>("an unverifiable server must abort the handshake") {
            pump.driveHandshake(client = client, server = server)
        }
        assertFalse(client.isHandshakeComplete, "client must not report a completed handshake on failure")

        client.close()
        server.close()
    }

    @Test
    fun `handshake fails when the client and server protocol ranges do not overlap`() {
        // Version negotiation happens before certificate verification, so
        // this failure is reachable even though a keel MbedTLS client
        // cannot complete a full handshake: server requires TLS 1.3,
        // client caps at TLS 1.2 → no common version → abort.
        val server = factory.createServerCodec(
            TlsConfig(certificates = serverCerts, verifyMode = TlsVerifyMode.NONE, minVersion = TlsVersion.TLS1_3),
        )
        val client = factory.createClientCodec(TlsConfig(maxVersion = TlsVersion.TLS1_2))

        assertFailsWith<TlsException>("non-overlapping version ranges must abort the handshake") {
            pump.driveHandshake(client = client, server = server)
        }
        assertFalse(server.isHandshakeComplete, "server must not complete when no common version exists")

        client.close()
        server.close()
    }

    @Test
    fun `SystemDefault trustAnchors is rejected on the Mbed TLS backend`() {
        // Mbed TLS has no portable system trust store, so SystemDefault must
        // fail fast at codec creation rather than silently mis-verify.
        assertFailsWith<TlsException>("SystemDefault must be rejected on Mbed TLS") {
            factory.createServerCodec(
                TlsConfig(certificates = serverCerts, trustAnchors = TlsTrustSource.SystemDefault),
            )
        }
    }
}
