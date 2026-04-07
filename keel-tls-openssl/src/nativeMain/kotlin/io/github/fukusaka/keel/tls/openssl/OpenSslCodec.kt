@file:OptIn(ExperimentalForeignApi::class)

package io.github.fukusaka.keel.tls.openssl

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.TlsCodecResult
import io.github.fukusaka.keel.tls.TlsErrorCategory
import io.github.fukusaka.keel.tls.TlsException
import io.github.fukusaka.keel.tls.TlsResult
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import openssl.SSL
import openssl.SSL_ERROR_NONE
import openssl.SSL_ERROR_WANT_READ
import openssl.SSL_ERROR_WANT_WRITE
import openssl.SSL_ERROR_ZERO_RETURN
import openssl.SSL_do_handshake
import openssl.SSL_free
import openssl.SSL_get_error
import openssl.SSL_is_init_finished
import openssl.SSL_read
import openssl.SSL_shutdown
import openssl.SSL_write
import openssl.keel_openssl_bio_ctx
import openssl.keel_openssl_err_string
import openssl.keel_openssl_get_alpn
import platform.posix.uint32_tVar

/**
 * [TlsCodec] implementation backed by OpenSSL 3.x with pointer-based BIO.
 *
 * Uses a custom [BIO_METHOD][openssl.BIO_meth_new] with recv/send callbacks
 * that read from and write to caller-owned [IoBuf] memory directly — no
 * intermediate buffer copies beyond the fundamental AEAD encrypt/decrypt.
 * This mirrors Mbed TLS's [keel_mbedtls_bio_ctx] approach.
 *
 * **Ownership**: [SSL_free] releases the BIO — do NOT free it separately.
 * The [bioCtx] is heap-allocated and freed in [close].
 *
 * **Thread model**: single EventLoop thread, same as all TlsCodec implementations.
 */
class OpenSslCodec internal constructor(
    private val ssl: CPointer<SSL>,
    private val bioCtx: keel_openssl_bio_ctx,
) : TlsCodec {

    private var closed = false

    override val isHandshakeComplete: Boolean
        get() = SSL_is_init_finished(ssl) == 1

    override val negotiatedProtocol: String?
        get() = memScoped {
            val lenVar = alloc<uint32_tVar>()
            val data = keel_openssl_get_alpn(ssl, lenVar.ptr)
            val len = lenVar.value.toInt()
            if (data == null || len == 0) return null
            data.readBytes(len).decodeToString()
        }

    override val peerCertificates: List<ByteArray>
        get() = emptyList() // Deferred: peer cert extraction requires i2d_X509 + OPENSSL_free.

    override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult {
        // Set recv pointer to ciphertext IoBuf.
        val cipherPtr = ciphertext.unsafePointer
        bioCtx.recv_ptr = (cipherPtr + ciphertext.readerIndex)!!.reinterpret<UByteVar>()
        bioCtx.recv_remaining = ciphertext.readableBytes.toULong()

        // Do NOT set send pointer during unprotect. Handshake responses
        // (ServerHello, Certificate, etc.) must go to the ciphertext output
        // of protect(), not the plaintext output here. If OpenSSL needs
        // to send during SSL_do_handshake/SSL_read, the write callback
        // returns -1 with BIO_set_retry_write, causing SSL_ERROR_WANT_WRITE
        // → NEED_WRAP — the caller then invokes protect() to flush.
        bioCtx.send_ptr = null
        bioCtx.send_capacity = 0u
        bioCtx.send_written = 0u

        val plainPtr = plaintext.unsafePointer

        // During handshake, use SSL_do_handshake. After handshake, use SSL_read.
        val ret = if (!isHandshakeComplete) {
            SSL_do_handshake(ssl)
        } else {
            SSL_read(
                ssl,
                (plainPtr + plaintext.writerIndex)!!.reinterpret<UByteVar>(),
                plaintext.writableBytes,
            )
        }

        val bytesConsumed = ciphertext.readableBytes - bioCtx.recv_remaining.toInt()

        // Reset recv pointers — only valid during this call.
        bioCtx.recv_ptr = null
        bioCtx.recv_remaining = 0u

        return when {
            ret > 0 -> {
                plaintext.writerIndex += ret
                TlsCodecResult(TlsResult.OK, bytesConsumed, ret)
            }
            ret == 0 && isHandshakeComplete -> {
                // SSL_do_handshake returned 0 = success. No plaintext yet.
                TlsCodecResult(TlsResult.OK, bytesConsumed, 0)
            }
            else -> {
                val err = SSL_get_error(ssl, ret)
                when (err) {
                    SSL_ERROR_WANT_READ ->
                        TlsCodecResult(TlsResult.NEED_MORE_INPUT, bytesConsumed, 0)
                    SSL_ERROR_WANT_WRITE ->
                        TlsCodecResult(TlsResult.NEED_WRAP, bytesConsumed, 0)
                    SSL_ERROR_ZERO_RETURN ->
                        TlsCodecResult(TlsResult.CLOSED, bytesConsumed, 0)
                    else -> throw tlsError("unprotect", err)
                }
            }
        }
    }

    override fun protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult {
        // Set send pointer to ciphertext output IoBuf.
        val cipherPtr = ciphertext.unsafePointer
        bioCtx.send_ptr = (cipherPtr + ciphertext.writerIndex)!!.reinterpret<UByteVar>()
        bioCtx.send_capacity = ciphertext.writableBytes.toULong()
        bioCtx.send_written = 0u

        val plainPtr = plaintext.unsafePointer
        val toWrite = plaintext.readableBytes

        // For handshake-only calls (empty plaintext), drive handshake forward.
        val ret = if (toWrite == 0 && !isHandshakeComplete) {
            SSL_do_handshake(ssl)
        } else if (toWrite > 0) {
            SSL_write(
                ssl,
                (plainPtr + plaintext.readerIndex)!!.reinterpret<UByteVar>(),
                toWrite,
            )
        } else {
            // Post-handshake, empty plaintext — nothing to do.
            0
        }

        val sendWritten = bioCtx.send_written.toInt()
        ciphertext.writerIndex += sendWritten

        // Reset send pointer.
        bioCtx.send_ptr = null

        return when {
            ret > 0 -> TlsCodecResult(TlsResult.OK, ret, sendWritten)
            ret == 0 && isHandshakeComplete ->
                TlsCodecResult(TlsResult.OK, 0, sendWritten)
            ret == 0 && !isHandshakeComplete && sendWritten > 0 ->
                TlsCodecResult(TlsResult.OK, 0, sendWritten)
            else -> {
                val err = SSL_get_error(ssl, ret)
                when (err) {
                    SSL_ERROR_NONE ->
                        TlsCodecResult(TlsResult.OK, 0, sendWritten)
                    SSL_ERROR_WANT_READ ->
                        TlsCodecResult(TlsResult.NEED_MORE_INPUT, 0, sendWritten)
                    SSL_ERROR_WANT_WRITE ->
                        TlsCodecResult(TlsResult.NEED_WRAP, 0, sendWritten)
                    SSL_ERROR_ZERO_RETURN ->
                        TlsCodecResult(TlsResult.CLOSED, 0, sendWritten)
                    else -> throw tlsError("protect", err)
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        SSL_shutdown(ssl)
        SSL_free(ssl) // Also frees the BIO.
        kotlinx.cinterop.nativeHeap.free(bioCtx.rawPtr)
    }

    private fun tlsError(op: String, sslError: Int): TlsException {
        val reason = keel_openssl_err_string()?.toKString() ?: "unknown"
        return TlsException(
            "OpenSSL $op failed: SSL_ERROR=$sslError ($reason)",
            TlsErrorCategory.PROTOCOL_ERROR,
            sslError.toLong(),
        )
    }
}
