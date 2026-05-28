package io.github.fukusaka.keel.tls.awslc

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsException
import io.github.fukusaka.keel.tls.TlsResult
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
 * **Scope note**: the mutual-TLS "missing required client certificate"
 * case is intentionally NOT covered here. keel's OpenSSL/AWS-LC factories
 * currently map `TlsVerifyMode.REQUIRED` to `SSL_VERIFY_PEER` without
 * `SSL_VERIFY_FAIL_IF_NO_PEER_CERT`, so a server does not abort on a
 * missing client cert — that gap is tracked separately. Version-downgrade
 * is covered via [TlsConfig.minVersion] / [maxVersion]; SNI-mismatch
 * remains out of scope (no hostname-verification knob yet).
 */
class AwsLcHandshakeErrorPathTest {

    private val allocator: BufferAllocator = DefaultAllocator
    private val factory = AwsLcCodecFactory()

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
            driveHandshake(client = client, server = server)
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
            TlsConfig(trustAnchors = TlsTrustSource.InsecureTrustAll, verifyMode = TlsVerifyMode.NONE, maxVersion = TlsVersion.TLS1_2),
        )

        assertFailsWith<TlsException>("non-overlapping version ranges must abort the handshake") {
            driveHandshake(client = client, server = server)
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

        driveHandshake(client = client, server = server)

        assertTrue(client.isHandshakeComplete, "client handshake must complete in the control case")
        assertTrue(server.isHandshakeComplete, "server handshake must complete in the control case")

        client.close()
        server.close()
    }

    // --- In-memory handshake pump (mirrors JsseHandshakeErrorPathTest) ---

    private fun driveHandshake(client: TlsCodec, server: TlsCodec) {
        var inFlight = stepCodec(client, ByteArray(0)) // ClientHello
        var rounds = 0
        while (rounds++ < MAX_ROUNDS) {
            if (client.isHandshakeComplete && server.isHandshakeComplete) return

            val serverOut = stepCodec(server, inFlight)
            if (client.isHandshakeComplete && server.isHandshakeComplete) return

            val clientOut = stepCodec(client, serverOut)
            inFlight = clientOut

            if (clientOut.isEmpty() && serverOut.isEmpty()) {
                if (client.isHandshakeComplete && server.isHandshakeComplete) return
                error("handshake stalled with no bytes in flight and handshake incomplete")
            }
        }
        error("handshake did not converge within $MAX_ROUNDS rounds")
    }

    private fun stepCodec(codec: TlsCodec, input: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        if (input.isNotEmpty()) {
            val cipherIn = allocator.allocate(input.size)
            cipherIn.writeByteArray(input, 0, input.size)
            val plain = allocator.allocate(PLAINTEXT_BUF)
            while (cipherIn.readableBytes > 0) {
                val r = codec.unprotect(cipherIn, plain)
                // The TlsCodec contract requires the caller to advance the
                // ciphertext readerIndex by bytesConsumed.
                cipherIn.readerIndex += r.bytesConsumed
                if (r.status == TlsResult.NEED_WRAP) {
                    drainProtect(codec, out)
                }
                if (r.bytesConsumed == 0 && r.status != TlsResult.NEED_WRAP) break
                if (r.status == TlsResult.CLOSED) break
            }
            plain.release()
            cipherIn.release()
        }
        drainProtect(codec, out)
        return out.toByteArray()
    }

    private fun drainProtect(codec: TlsCodec, out: MutableList<Byte>) {
        val emptyPlain = allocator.allocate(EMPTY_PLAINTEXT_BUF)
        try {
            while (true) {
                val cipher = allocator.allocate(CIPHERTEXT_BUF)
                val r = codec.protect(emptyPlain, cipher)
                val produced = cipher.readableBytes
                for (i in 0 until produced) out.add(cipher.getByte(cipher.readerIndex + i))
                cipher.release()
                if (r.status != TlsResult.NEED_WRAP) break
            }
        } finally {
            emptyPlain.release()
        }
    }

    private companion object {
        private const val CIPHERTEXT_BUF = 18_432
        private const val PLAINTEXT_BUF = 18_432
        private const val EMPTY_PLAINTEXT_BUF = 16
        private const val MAX_ROUNDS = 32
    }
}
