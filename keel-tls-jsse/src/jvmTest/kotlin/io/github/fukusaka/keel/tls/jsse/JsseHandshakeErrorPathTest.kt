package io.github.fukusaka.keel.tls.jsse

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsErrorCategory
import io.github.fukusaka.keel.tls.TlsException
import io.github.fukusaka.keel.tls.TlsResult
import io.github.fukusaka.keel.tls.TlsTrustSource
import io.github.fukusaka.keel.tls.TlsVerifyMode
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
 * **Scope note**: TLS version-downgrade and SNI-hostname-mismatch
 * failures are not covered here because keel's [TlsConfig] surface
 * exposes neither a protocol-version floor nor SSLEngine endpoint
 * identification (`setEndpointIdentificationAlgorithm("HTTPS")`), so
 * neither failure can be induced through the public config. They remain
 * follow-ups gated on those config knobs existing.
 */
class JsseHandshakeErrorPathTest {

    private val allocator: BufferAllocator = DefaultAllocator
    private val factory = JsseTlsCodecFactory()

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
            driveHandshake(client = client, server = server)
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
            driveHandshake(client = client, server = server)
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

        driveHandshake(client = client, server = server)

        assertTrue(client.isHandshakeComplete, "client handshake must complete in the control case")
        assertTrue(server.isHandshakeComplete, "server handshake must complete in the control case")

        client.close()
        server.close()
    }

    // --- In-memory handshake pump ---

    /**
     * Drives a TLS handshake between [client] and [server] entirely in
     * memory. The client speaks first (ClientHello); thereafter each
     * side's outbound records are fed to the peer until both complete or
     * the round budget is exhausted. Any [TlsException] thrown by a codec
     * propagates to the caller.
     */
    private fun driveHandshake(client: TlsCodec, server: TlsCodec) {
        var inFlight = stepCodec(client, ByteArray(0)) // produce ClientHello
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

    /**
     * Feeds [input] (peer ciphertext, possibly empty) into [codec] and
     * returns all ciphertext the codec produces in response. Inbound
     * records are unwrapped one at a time; whenever the codec signals
     * [TlsResult.NEED_WRAP] (or after the input is drained) the codec's
     * pending outbound handshake records are wrapped and collected.
     */
    private fun stepCodec(codec: TlsCodec, input: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        if (input.isNotEmpty()) {
            val cipherIn = allocator.allocate(input.size)
            cipherIn.writeByteArray(input, 0, input.size)
            val plain = allocator.allocate(PLAINTEXT_BUF)
            while (cipherIn.readableBytes > 0) {
                val r = codec.unprotect(cipherIn, plain)
                // Per the TlsCodec contract the caller advances the
                // ciphertext readerIndex by bytesConsumed; without this the
                // same record is re-fed and the AEAD key schedule desyncs.
                cipherIn.readerIndex += r.bytesConsumed
                if (r.status == TlsResult.NEED_WRAP) {
                    drainProtect(codec, out)
                }
                // Stop if the codec made no progress and is not asking to
                // wrap — feeding the same bytes again would spin forever.
                if (r.bytesConsumed == 0 && r.status != TlsResult.NEED_WRAP) break
                if (r.status == TlsResult.CLOSED) break
            }
            plain.release()
            cipherIn.release()
        }
        // Drain any pending outbound flight (ClientHello on the first
        // call, the client Finished after consuming the server flight,
        // etc.). On a codec with nothing to send this is a cheap no-op.
        drainProtect(codec, out)
        return out.toByteArray()
    }

    /**
     * Wraps [codec]'s pending outbound records (with empty plaintext)
     * into [out], looping while the codec reports [TlsResult.NEED_WRAP]
     * so a multi-record handshake flight is fully produced.
     */
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
        // TLS records cap at 2^14 + overhead; size handshake buffers
        // generously so a full flight (certificate chain) fits.
        private const val CIPHERTEXT_BUF = 18_432
        private const val PLAINTEXT_BUF = 18_432
        private const val EMPTY_PLAINTEXT_BUF = 16

        // Handshake completes in a handful of round-trips; the bound just
        // guards against a pump bug spinning forever.
        private const val MAX_ROUNDS = 32
    }
}
