package io.github.fukusaka.keel.tls.jsse

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsErrorCategory
import io.github.fukusaka.keel.tls.TlsException
import io.github.fukusaka.keel.tls.TlsTrustSource
import io.github.fukusaka.keel.tls.TlsVerifyMode
import io.github.fukusaka.keel.tls.TlsVersion
import javax.net.ssl.SSLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Handshake **error-path** coverage for the JSSE backend.
 *
 * The existing HTTPS tests ([JsseHttpsEchoTest] etc.) drive only the
 * happy path (a curl client completing a handshake against a trusted
 * server), and [JsseTlsCodecTest] covers config-creation failures
 * (malformed PEM). Neither exercises a *handshake that fails on the
 * wire* — the case a production server must classify and tear down
 * cleanly. This test fills that gap.
 *
 * Rather than open real sockets (the raw-FFI echo tests fork curl,
 * which is heavy and not interruptible), it drives two real
 * [javax.net.ssl.SSLEngine]-backed codecs against each other entirely
 * in memory via [TlsCodec.protect] / [unprotect]. The handshake pump
 * ([driveHandshake]) shuttles each side's outbound TLS records into the
 * peer's inbound path until both complete, a [TlsException] is thrown,
 * or the round budget is exhausted — fully deterministic, no kernel,
 * runs in CI on every JVM host.
 *
 * The pinned contract is the JSSE error mapping: [JsseTlsCodec] funnels
 * every [SSLException] (handshake failure, certificate rejection, …)
 * into a [TlsException] with [TlsErrorCategory.PROTOCOL_ERROR] and the
 * original `SSLException` as the cause. The richer categories
 * ([HANDSHAKE_FAILED][TlsErrorCategory.HANDSHAKE_FAILED] /
 * [CERTIFICATE_INVALID][TlsErrorCategory.CERTIFICATE_INVALID]) are
 * intentionally collapsed here — JSSE does not expose a stable
 * machine-readable sub-classification, so the backend keeps the cause
 * for diagnostics instead.
 *
 * **Scope note**: version-downgrade is covered via
 * [TlsConfig.minVersion] / [maxVersion]. SNI-hostname-mismatch lives in
 * [JsseHostnameVerificationTest], which uses [TlsConfig.verifyHostname]
 * (wired to `setEndpointIdentificationAlgorithm("HTTPS")`) to induce and
 * pin the mismatch.
 */
class JsseHandshakeErrorPathTest {

    private val allocator: BufferAllocator = DefaultAllocator
    private val factory = JsseTlsCodecFactory()
    private val pump = JsseHandshakePump(allocator)

    private val serverCerts = TlsCertificateSource.Pem(
        TestCertificates.SERVER_CERT,
        TestCertificates.SERVER_KEY,
    )

    // --- Tests ---

    @Test
    fun `handshake against an untrusted self-signed server fails with TlsException`() {
        // Server presents the self-signed test cert; client validates
        // against the JDK system trust store (trustAnchors = SystemDefault),
        // which does not contain it. The client's unwrap of the server
        // Certificate message throws, surfacing as a TlsException.
        val server = factory.createServerCodec(
            TlsConfig(certificates = serverCerts, verifyMode = TlsVerifyMode.NONE),
        )
        val client = factory.createClientCodec(
            TlsConfig(trustAnchors = TlsTrustSource.SystemDefault, verifyMode = TlsVerifyMode.NONE),
        )

        val error = assertFailsWith<TlsException> {
            pump.driveHandshake(client = client, server = server)
        }
        assertEquals(
            TlsErrorCategory.PROTOCOL_ERROR,
            error.category,
            "JSSE maps every SSLException (including certificate rejection) to PROTOCOL_ERROR",
        )
        assertIs<SSLException>(error.cause, "the originating SSLException must be retained as the cause")
        assertFalse(client.isHandshakeComplete, "client must not report a completed handshake on failure")

        client.close()
        server.close()
    }

    @Test
    fun `handshake requiring a client certificate fails when the client presents none`() {
        // Server requires mutual TLS (verifyMode = REQUIRED) but the
        // client has no certificate configured. The server aborts the
        // handshake, surfacing as a TlsException on the server codec.
        val server = factory.createServerCodec(
            TlsConfig(
                certificates = serverCerts,
                verifyMode = TlsVerifyMode.REQUIRED,
                trustAnchors = TlsTrustSource.InsecureTrustAll,
            ),
        )
        val client = factory.createClientCodec(
            TlsConfig(trustAnchors = TlsTrustSource.InsecureTrustAll, verifyMode = TlsVerifyMode.NONE),
        )

        val error = assertFailsWith<TlsException> {
            pump.driveHandshake(client = client, server = server)
        }
        assertEquals(
            TlsErrorCategory.PROTOCOL_ERROR,
            error.category,
            "a missing required client certificate must surface as a PROTOCOL_ERROR TlsException",
        )
        assertIs<SSLException>(error.cause, "the originating SSLException must be retained as the cause")

        client.close()
        server.close()
    }

    @Test
    fun `handshake fails when the client and server protocol ranges do not overlap`() {
        // Server requires TLS 1.3; client caps at TLS 1.2 → no common
        // version → the handshake aborts. The client trusts the server
        // (InsecureTrustAll) so the failure is the version mismatch, not
        // certificate rejection.
        val server = factory.createServerCodec(
            TlsConfig(certificates = serverCerts, verifyMode = TlsVerifyMode.NONE, minVersion = TlsVersion.TLS1_3),
        )
        val client = factory.createClientCodec(
            TlsConfig(
                trustAnchors = TlsTrustSource.InsecureTrustAll,
                verifyMode = TlsVerifyMode.NONE,
                maxVersion = TlsVersion.TLS1_2,
            ),
        )

        assertFailsWith<TlsException>("non-overlapping version ranges must abort the handshake") {
            pump.driveHandshake(client = client, server = server)
        }
        assertFalse(server.isHandshakeComplete, "server must not complete when no common version exists")

        client.close()
        server.close()
    }

    @Test
    fun `handshake completes when the client trusts the server (pump control)`() {
        // Control case: validates that driveHandshake actually converges
        // on a well-configured pair, so the two failure assertions above
        // are trusted to fail for the right reason (a real handshake
        // abort) rather than a broken pump.
        val server = factory.createServerCodec(
            TlsConfig(certificates = serverCerts, verifyMode = TlsVerifyMode.NONE),
        )
        val client = factory.createClientCodec(
            TlsConfig(trustAnchors = TlsTrustSource.InsecureTrustAll, verifyMode = TlsVerifyMode.NONE),
        )

        pump.driveHandshake(client = client, server = server)

        assertTrue(client.isHandshakeComplete, "client handshake must complete in the control case")
        assertTrue(server.isHandshakeComplete, "server handshake must complete in the control case")

        client.close()
        server.close()
    }
}
