package io.github.fukusaka.keel.tls.jsse

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.TlsResult

/**
 * In-memory handshake pump shared by the JSSE handshake tests
 * ([JsseHandshakeErrorPathTest] / [JsseHostnameVerificationTest]).
 *
 * Drives two [JsseTlsCodec]s against each other entirely in memory via
 * [TlsCodec.protect] / [TlsCodec.unprotect] — no socket — so handshake
 * outcomes can be pinned deterministically and synchronously.
 */
internal class JsseHandshakePump(private val allocator: BufferAllocator) {

    /**
     * Drives a TLS handshake between [client] and [server] entirely in
     * memory. The client speaks first (ClientHello); thereafter each side's
     * outbound records are fed to the peer until both complete. Any
     * [io.github.fukusaka.keel.tls.TlsException] thrown by a codec propagates.
     */
    fun driveHandshake(client: TlsCodec, server: TlsCodec) {
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

    private fun stepCodec(codec: TlsCodec, input: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        if (input.isNotEmpty()) {
            val cipherIn = allocator.allocate(input.size)
            cipherIn.writeByteArray(input, 0, input.size)
            val plain = allocator.allocate(PLAINTEXT_BUF)
            while (cipherIn.readableBytes > 0) {
                val r = codec.unprotect(cipherIn, plain)
                // Per the TlsCodec contract the caller advances the ciphertext
                // readerIndex by bytesConsumed; without this the same record is
                // re-fed and the AEAD key schedule desyncs.
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
