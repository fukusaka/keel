package io.github.fukusaka.keel.tls.mbedtls

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsException
import io.github.fukusaka.keel.tls.TlsResult
import io.github.fukusaka.keel.tls.TlsTrustSource
import io.github.fukusaka.keel.tls.TlsVerifyMode
import io.github.fukusaka.keel.tls.openssl.OpenSslCodecFactory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Mutual-TLS enforcement for the MbedTLS **server** authmode (the K53
 * sibling fix: keel's MbedTLS factory now wires [TlsConfig.verifyMode]
 * into `mbedtls_ssl_conf_authmode`, which it previously omitted — so a
 * `REQUIRED` server stayed at MbedTLS's default `VERIFY_NONE` and silently
 * accepted a cert-less client).
 *
 * A keel MbedTLS *client* cannot complete an in-memory handshake (the
 * factory never wires the hostname MbedTLS verification requires), so this
 * pairs the MbedTLS **server** with an OpenSSL **client** that presents no
 * certificate. The client uses `verifyMode = NONE` so it does not verify
 * the self-signed server — isolating the failure to the server's
 * missing-client-cert rejection.
 *
 * Red-Green: pre-fix (`REQUIRED` mapped to MbedTLS's default, i.e. the
 * server never requested a client cert) the cert-less OpenSSL client
 * completes the handshake and this test fails; post-fix (`REQUIRED` →
 * `MBEDTLS_SSL_VERIFY_REQUIRED`) the server aborts and the handshake
 * surfaces a [TlsException].
 *
 * Lives in the `opensslPeerTest` source set (macosArm64 + linuxX64 only,
 * where `keel-tls-openssl` is built); uses only OpenSSL's pure-Kotlin
 * `TlsCodecFactory` API.
 */
class MbedTlsMutualTlsTest {

    private val allocator: BufferAllocator = DefaultAllocator
    private val mbedTlsFactory = MbedTlsCodecFactory()
    private val openSslFactory = OpenSslCodecFactory()

    private val serverCerts = TlsCertificateSource.Pem(
        TestCertificates.SERVER_CERT,
        TestCertificates.SERVER_KEY,
    )

    @Test
    fun `a REQUIRED MbedTLS server aborts when the client presents no certificate`() {
        val server = mbedTlsFactory.createServerCodec(
            TlsConfig(certificates = serverCerts, verifyMode = TlsVerifyMode.REQUIRED),
        )
        val client = openSslFactory.createClientCodec(
            TlsConfig(trustAnchors = TlsTrustSource.InsecureTrustAll, verifyMode = TlsVerifyMode.NONE),
        )

        assertFailsWith<TlsException>("a missing required client certificate must abort the handshake") {
            driveHandshake(client = client, server = server)
        }
        assertFalse(server.isHandshakeComplete, "server must not complete without the required client cert")

        server.close()
        client.close()
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
