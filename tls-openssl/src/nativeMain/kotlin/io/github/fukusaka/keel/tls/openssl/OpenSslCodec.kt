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
import openssl.BIO
import openssl.BIO_ctrl_pending
import openssl.BIO_read
import openssl.BIO_write
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
import openssl.keel_openssl_err_string
import openssl.keel_openssl_get_alpn
import platform.posix.uint32_tVar

/**
 * [TlsCodec] implementation backed by OpenSSL 3.x with memory BIO.
 *
 * Uses [BIO_s_mem][openssl.BIO_s_mem] for transport: ciphertext is fed
 * into [readBio] via [BIO_write], and encrypted output is drained from
 * [writeBio] via [BIO_read]. This involves a copy per direction (unlike
 * Mbed TLS's pointer-based BIO), which is acceptable for the initial
 * implementation. Future optimization: custom BIO method via
 * [BIO_meth_new][openssl.BIO_meth_new] for zero-copy.
 *
 * **Ownership**: [SSL_free] releases both BIOs — do NOT free them separately.
 *
 * **Thread model**: single EventLoop thread, same as all TlsCodec implementations.
 */
class OpenSslCodec internal constructor(
    private val ssl: CPointer<SSL>,
    private val readBio: CPointer<BIO>,
    private val writeBio: CPointer<BIO>,
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
        val cipherPtr = ciphertext.unsafePointer

        // Feed all available ciphertext into the read BIO.
        val readable = ciphertext.readableBytes
        if (readable > 0) {
            BIO_write(
                readBio,
                (cipherPtr + ciphertext.readerIndex)!!.reinterpret<UByteVar>(),
                readable,
            )
        }

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

        // Bytes consumed = what we wrote minus what the BIO still holds.
        val bytesConsumed = readable - BIO_ctrl_pending(readBio).toInt()

        // Check if the write BIO has pending handshake output that needs
        // to be flushed via protect(). This is critical during handshake:
        // SSL_do_handshake may produce output (e.g., ServerHello) even
        // when it returns SSL_ERROR_WANT_READ for the next flight.
        val hasWritePending = BIO_ctrl_pending(writeBio).toInt() > 0

        return when {
            ret > 0 -> {
                plaintext.writerIndex += ret
                val status = if (hasWritePending) TlsResult.NEED_WRAP else TlsResult.OK
                TlsCodecResult(status, bytesConsumed, ret)
            }
            ret == 0 && isHandshakeComplete -> {
                // SSL_do_handshake returned 0 = success. No plaintext yet.
                val status = if (hasWritePending) TlsResult.NEED_WRAP else TlsResult.OK
                TlsCodecResult(status, bytesConsumed, 0)
            }
            else -> {
                val err = SSL_get_error(ssl, ret)
                when (err) {
                    SSL_ERROR_WANT_READ -> {
                        val status = if (hasWritePending) TlsResult.NEED_WRAP else TlsResult.NEED_MORE_INPUT
                        TlsCodecResult(status, bytesConsumed, 0)
                    }
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

        // Drain the write BIO into the ciphertext output.
        val cipherPtr = ciphertext.unsafePointer
        val pending = BIO_ctrl_pending(writeBio).toInt()
        val bytesProduced = if (pending > 0) {
            BIO_read(
                writeBio,
                (cipherPtr + ciphertext.writerIndex)!!.reinterpret<UByteVar>(),
                minOf(pending, ciphertext.writableBytes),
            )
        } else {
            0
        }
        if (bytesProduced > 0) {
            ciphertext.writerIndex += bytesProduced
        }

        return when {
            ret > 0 -> TlsCodecResult(TlsResult.OK, ret, bytesProduced)
            ret == 0 && isHandshakeComplete ->
                TlsCodecResult(TlsResult.OK, 0, bytesProduced)
            ret == 0 && !isHandshakeComplete && bytesProduced > 0 ->
                // Handshake in progress, produced output — caller should flush.
                TlsCodecResult(TlsResult.OK, 0, bytesProduced)
            else -> {
                val err = SSL_get_error(ssl, ret)
                when (err) {
                    SSL_ERROR_NONE ->
                        TlsCodecResult(TlsResult.OK, 0, bytesProduced)
                    SSL_ERROR_WANT_READ ->
                        TlsCodecResult(TlsResult.NEED_MORE_INPUT, 0, bytesProduced)
                    SSL_ERROR_WANT_WRITE ->
                        TlsCodecResult(TlsResult.NEED_WRAP, 0, bytesProduced)
                    SSL_ERROR_ZERO_RETURN ->
                        TlsCodecResult(TlsResult.CLOSED, 0, bytesProduced)
                    else -> throw tlsError("protect", err)
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        SSL_shutdown(ssl)
        SSL_free(ssl) // Also frees both BIOs.
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
