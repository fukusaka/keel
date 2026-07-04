package io.github.fukusaka.keel.tls.mbedtls

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.TlsResult

/**
 * In-memory handshake pump shared by the MbedTLS handshake tests
 * ([MbedTlsHandshakeErrorPathTest] / [MbedTlsHostnameVerificationTest]).
 *
 * Drives two real codecs against each other entirely in memory via
 * [TlsCodec.protect] / [TlsCodec.unprotect] (the MbedTLS codec uses an
 * in-memory pointer-based BIO — no fd / socket), so handshake outcomes
 * can be pinned deterministically and synchronously. Extracted verbatim
 * from `MbedTlsHandshakeErrorPathTest` (mirrors
 * `JsseHandshakeErrorPathTest`'s pump).
 */
internal class MbedTlsHandshakePump(private val allocator: BufferAllocator) {

    /**
     * Pumps records between [client] and [server] until both report a
     * completed handshake, or throws: a handshake abort surfaces as the
     * codec's own exception (e.g. `TlsException`), a stall or
     * non-convergence as [IllegalStateException] from [error].
     */
    fun driveHandshake(client: TlsCodec, server: TlsCodec) {
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

    /** Feeds [input] ciphertext into [codec] and returns the ciphertext it produces. */
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
