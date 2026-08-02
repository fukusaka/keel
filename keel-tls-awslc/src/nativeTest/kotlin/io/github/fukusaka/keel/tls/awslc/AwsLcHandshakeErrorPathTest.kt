package io.github.fukusaka.keel.tls.awslc

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
import kotlin.test.assertTrue

/**
 * Handshake **error-path** coverage for the AWS-LC backend.
 *
 * The existing OpenSSL tests drive only the happy path (`AwsLcEchoTest`
 * / `AwsLcHttpsEchoTest` complete a handshake against a trusted
 * server) or config-creation failures (`AwsLcCodecTest`). Neither
 * exercises a handshake that **fails on the wire**. This test mirrors
 * the JSSE backend's `JsseHandshakeErrorPathTest` so the two codec
 * families (JSSE `SSLEngine`, AWS-LC memory-BIO) are pinned to the same
 * contract: a real handshake failure surfaces as a structured
 * [TlsException] rather than a hang or a silent miscompletion.
 *
 * Like the JSSE test it drives two real codecs against each other
 * **entirely in memory** via [TlsCodec.protect] / [unprotect] — the
 * OpenSSL codec uses an in-memory BIO context (no fd, no socket), so the
 * same handshake pump applies. Fully deterministic; runs on macosArm64
 * locally and linuxX64 in CI.
 *
 * **Failure vehicle**: an untrusted self-signed server certificate. The
 * client verifies the peer (`verifyMode = PEER`) against the system
 * trust store ([TlsTrustSource.SystemDefault] →
 * `SSL_CTX_set_default_verify_paths`), which never contains the
 * self-signed test cert, so the client aborts when it validates the
 * server Certificate message. Unlike JSSE (whose client always verifies
 * the server regardless of `verifyMode`), AWS-LC honours `verifyMode`,
 * so `PEER` is required here — `NONE` would disable verification and the
 * handshake would succeed.
 *
 * Also pins mutual-TLS enforcement: a `REQUIRED` server aborts when the
 * client presents no certificate (`SSL_VERIFY_PEER |
 * SSL_VERIFY_FAIL_IF_NO_PEER_CERT`), while a `PEER` server accepts the
 * cert-less client. Version-downgrade is covered via [TlsConfig.minVersion]
 * / [maxVersion]; SNI-mismatch remains out of scope (no
 * hostname-verification knob yet).
 */
class AwsLcHandshakeErrorPathTest {

    private val allocator: BufferAllocator = DefaultAllocator
    private val factory = AwsLcCodecFactory()
    private val pump = AwsLcHandshakePump(allocator)

    private val serverCerts = TlsCertificateSource.Pem(
        TestCertificates.SERVER_CERT,
        TestCertificates.SERVER_KEY,
    )

    @Test
    fun `handshake against an untrusted self-signed server fails with TlsException`() {
        val server = factory.createServerCodec(
            TlsConfig(certificates = serverCerts, verifyMode = TlsVerifyMode.NONE),
        )
        val client = factory.createClientCodec(
            TlsConfig(trustAnchors = TlsTrustSource.SystemDefault, verifyMode = TlsVerifyMode.PEER),
        )

        assertFailsWith<TlsException>("untrusted server cert must abort the handshake") {
            pump.driveHandshake(client = client, server = server)
        }
        assertFalse(client.isHandshakeComplete, "client must not report a completed handshake on failure")

        client.close()
        server.close()
    }

    @Test
    fun `handshake fails when the client and server protocol ranges do not overlap`() {
        // Server requires TLS 1.3; client caps at TLS 1.2 → no common
        // version → abort. Client trusts the server (InsecureTrustAll) so
        // the failure is the version mismatch, not certificate rejection.
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
    fun `handshake completes when the client trusts the server as a pump control`() {
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

    @Test
    fun `a REQUIRED server aborts when the client presents no certificate`() {
        val server = factory.createServerCodec(
            TlsConfig(certificates = serverCerts, verifyMode = TlsVerifyMode.REQUIRED),
        )
        val client = factory.createClientCodec(
            TlsConfig(trustAnchors = TlsTrustSource.InsecureTrustAll, verifyMode = TlsVerifyMode.NONE),
        )

        assertFailsWith<TlsException>("a missing required client certificate must abort the handshake") {
            pump.driveHandshake(client = client, server = server)
        }
        assertFalse(server.isHandshakeComplete, "server must not complete without the required client cert")

        client.close()
        server.close()
    }

    @Test
    fun `a PEER server completes when the client presents no certificate`() {
        val server = factory.createServerCodec(
            TlsConfig(certificates = serverCerts, verifyMode = TlsVerifyMode.PEER),
        )
        val client = factory.createClientCodec(
            TlsConfig(trustAnchors = TlsTrustSource.InsecureTrustAll, verifyMode = TlsVerifyMode.NONE),
        )

        pump.driveHandshake(client = client, server = server)

        assertTrue(server.isHandshakeComplete, "a PEER server accepts a cert-less client")
        assertTrue(client.isHandshakeComplete, "client handshake completes against a PEER server")

        client.close()
        server.close()
    }
}
