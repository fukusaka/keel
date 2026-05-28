package io.github.fukusaka.keel.tls.mbedtls

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsResult
import io.github.fukusaka.keel.tls.TlsTrustSource
import io.github.fukusaka.keel.tls.TlsVerifyMode
import io.github.fukusaka.keel.tls.openssl.OpenSslCodecFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Red-Green regression for the MbedTLS `close()` null-pointer-offset
 * write (the companion fix to [MbedTlsHandshakeErrorPathTest]).
 *
 * The bug: `MbedTlsCodec.protect()` reset only `send_ptr`, leaving
 * `send_capacity` / `send_written` stale, so `close()`'s
 * `mbedtls_ssl_close_notify` write computed `avail > 0` and `memcpy`-ed
 * into `null + send_written`. It is only reachable after a **completed**
 * handshake (close_notify is not sent on an aborted one), and a keel
 * MbedTLS *client* cannot complete an in-memory handshake (the factory
 * never wires the hostname MbedTLS verification requires). So this test
 * pairs the MbedTLS **server** with an OpenSSL **client** — OpenSSL
 * honours `verifyMode` / `trustAnchors`, so an `InsecureTrustAll` client
 * completes the handshake against the self-signed MbedTLS server.
 *
 * After the handshake the server's last codec operation is a `protect`
 * (its handshake flight), leaving the send pointer null but
 * capacity/written stale — exactly the state that makes
 * `server.close()` fault pre-fix. The test therefore SIGSEGVs before the
 * fix and passes after it.
 *
 * Lives in the `opensslPeerTest` source set (macosArm64 + linuxX64 only,
 * where `keel-tls-openssl` is built); it uses only OpenSSL's pure-Kotlin
 * `TlsCodecFactory` API, no openssl cinterop types.
 */
class MbedTlsServerCloseRegressionTest {

    private val allocator: BufferAllocator = DefaultAllocator
    private val mbedTlsFactory = MbedTlsCodecFactory()
    private val openSslFactory = OpenSslCodecFactory()

    private val serverCerts = TlsCertificateSource.Pem(
        TestCertificates.SERVER_CERT,
        TestCertificates.SERVER_KEY,
    )

    @Test
    fun `MbedTLS server close after a completed handshake does not fault`() {
        val server = mbedTlsFactory.createServerCodec(
            TlsConfig(certificates = serverCerts, verifyMode = TlsVerifyMode.NONE),
        )
        // OpenSSL client trusting anything completes the handshake against
        // the self-signed MbedTLS server.
        val client = openSslFactory.createClientCodec(
            TlsConfig(trustAnchors = TlsTrustSource.InsecureTrustAll, verifyMode = TlsVerifyMode.NONE),
        )

        driveHandshake(client = client, server = server)

        assertTrue(server.isHandshakeComplete, "MbedTLS server handshake must complete")
        assertTrue(client.isHandshakeComplete, "OpenSSL client handshake must complete")

        // The regression: pre-fix this SIGSEGVs inside
        // mbedtls_ssl_close_notify's send-BIO callback (null + send_written).
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
