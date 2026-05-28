package io.github.fukusaka.keel.tls.mbedtls

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsException
import io.github.fukusaka.keel.tls.TlsResult
import io.github.fukusaka.keel.tls.TlsVerifyMode
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
 * **Why only the failure case** (unlike the JSSE / OpenSSL / AWS-LC
 * sibling tests, which also drive a success-then-close control): keel's
 * MbedTLS factory does not wire [TlsConfig.verifyMode],
 * [TlsConfig.trustAnchors], or [TlsConfig.serverName] into the
 * `mbedtls_ssl_config`. A keel MbedTLS *client* therefore always runs
 * the default preset's peer verification but is never given the hostname
 * MbedTLS requires (`mbedtls_ssl_set_hostname`), so an in-memory
 * client↔server handshake cannot be completed through the public API —
 * the client aborts with "verify a certificate without an expected
 * hostname". That same unconfigurable verification is the failure
 * vehicle here.
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

    private val serverCerts = TlsCertificateSource.Pem(
        TestCertificates.SERVER_CERT,
        TestCertificates.SERVER_KEY,
    )

    @Test
    fun `handshake fails with TlsException when the client cannot verify the server`() {
        val server = factory.createServerCodec(
            TlsConfig(certificates = serverCerts, verifyMode = TlsVerifyMode.NONE),
        )
        // A keel MbedTLS client cannot be configured to trust the
        // self-signed server (verifyMode / trustAnchors / serverName are
        // not wired), so the default peer verification aborts the
        // handshake — surfacing as a TlsException through the pump.
        val client = factory.createClientCodec(TlsConfig())

        assertFailsWith<TlsException>("an unverifiable server must abort the handshake") {
            driveHandshake(client = client, server = server)
        }
        assertFalse(client.isHandshakeComplete, "client must not report a completed handshake on failure")

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
