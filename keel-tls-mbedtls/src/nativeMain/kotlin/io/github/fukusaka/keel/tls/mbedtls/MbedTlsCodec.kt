@file:OptIn(ExperimentalForeignApi::class, UnsafeIoBufApi::class)

package io.github.fukusaka.keel.tls.mbedtls

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.TlsCodecResult
import io.github.fukusaka.keel.tls.TlsErrorCategory
import io.github.fukusaka.keel.tls.TlsException
import io.github.fukusaka.keel.tls.TlsResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import mbedtls.MBEDTLS_ERR_SSL_PEER_CLOSE_NOTIFY
import mbedtls.MBEDTLS_ERR_SSL_WANT_READ
import mbedtls.MBEDTLS_ERR_SSL_WANT_WRITE
import mbedtls.keel_mbedtls_bio_ctx
import mbedtls.keel_mbedtls_bio_setup
import mbedtls.keel_mbedtls_strerror
import mbedtls.mbedtls_ssl_close_notify
import mbedtls.mbedtls_ssl_context
import mbedtls.mbedtls_ssl_free
import mbedtls.mbedtls_ssl_get_alpn_protocol
import mbedtls.mbedtls_ssl_handshake
import mbedtls.mbedtls_ssl_init
import mbedtls.mbedtls_ssl_is_handshake_over
import mbedtls.mbedtls_ssl_read
import mbedtls.mbedtls_ssl_setup
import mbedtls.mbedtls_ssl_write

/**
 * [TlsCodec] implementation backed by Mbed TLS 4.x.
 *
 * Uses a pointer-based BIO adapter: recv/send callbacks read from and
 * write to caller-owned IoBuf memory directly — no intermediate buffer
 * copies beyond the fundamental AEAD encrypt/decrypt.
 *
 * **Shared session**: the X.509 cert chain, private key, and
 * `mbedtls_ssl_config` are owned by [MbedTlsServerSession] (created
 * once per factory + [io.github.fukusaka.keel.tls.TlsConfig]) and
 * shared across every codec derived from it via
 * `mbedtls_ssl_setup(ssl, conf)`. Mbed TLS treats config as read-only
 * after setup, so concurrent ssl_context use is safe. This avoids the
 * pre-K53 per-codec `psa_crypto_init` + `x509_crt_parse` race that
 * crashed multi-worker servers under load.
 *
 * **Lifecycle**: [close] frees the per-connection
 * `mbedtls_ssl_context` + BIO context, then releases this codec's
 * reference to the shared session. The session's underlying
 * `mbedtls_ssl_config` / cert / key live until the last referent
 * (factory or in-flight codec) releases, so codec close and factory
 * close are order-independent.
 */
class MbedTlsCodec internal constructor(
    private val session: MbedTlsServerSession,
) : TlsCodec {

    private val ssl = nativeHeap.alloc<mbedtls_ssl_context>()
    private val bioCtx = nativeHeap.alloc<keel_mbedtls_bio_ctx>()

    private var closed = false

    init {
        // The session arrives with one reference pre-acquired by
        // MbedTlsCodecFactory.acquireSession on our behalf — see
        // its KDoc for the race-window rationale. We do *not*
        // retain again; we only release in close() (or on a
        // construction-time throw below).
        try {
            mbedtls_ssl_init(ssl.ptr)
            val ret = mbedtls_ssl_setup(ssl.ptr, session.conf.ptr)
            checkMbedTls(ret, "ssl_setup")
            keel_mbedtls_bio_setup(ssl.ptr, bioCtx.ptr)
        } catch (e: Throwable) {
            // Roll back the partial heap allocations + the
            // pre-acquired session ref so a failed setup doesn't
            // leak any of them.
            nativeHeap.free(ssl.rawPtr)
            nativeHeap.free(bioCtx.rawPtr)
            session.release()
            throw e
        }
    }

    // --- TlsCodec ---

    override val isHandshakeComplete: Boolean
        get() = mbedtls_ssl_is_handshake_over(ssl.ptr) == 1

    override val negotiatedProtocol: String?
        get() = mbedtls_ssl_get_alpn_protocol(ssl.ptr)?.toKString()

    override val peerCertificates: List<ByteArray>
        get() = emptyList() // Peer cert extraction is deferred.

    override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult {
        val cipherPtr = ciphertext.unsafePointer
        bioCtx.recv_ptr = (cipherPtr + ciphertext.readerIndex)!!.reinterpret<UByteVar>()
        bioCtx.recv_remaining = ciphertext.readableBytes.toULong()

        bioCtx.send_ptr = null
        bioCtx.send_capacity = 0u
        bioCtx.send_written = 0u

        val plainPtr = plaintext.unsafePointer

        val ret = if (!isHandshakeComplete) {
            mbedtls_ssl_handshake(ssl.ptr)
        } else {
            mbedtls_ssl_read(
                ssl.ptr,
                (plainPtr + plaintext.writerIndex)!!.reinterpret<UByteVar>(),
                plaintext.writableBytes.toULong(),
            )
        }

        val bytesConsumed = ciphertext.readableBytes - bioCtx.recv_remaining.toInt()

        bioCtx.recv_ptr = null
        bioCtx.recv_remaining = 0u

        return when {
            ret > 0 -> {
                plaintext.writerIndex += ret
                TlsCodecResult(TlsResult.OK, bytesConsumed, ret)
            }
            ret == 0 && isHandshakeComplete -> {
                TlsCodecResult(TlsResult.OK, bytesConsumed, 0)
            }
            ret == MBEDTLS_ERR_SSL_WANT_READ ->
                TlsCodecResult(TlsResult.NEED_MORE_INPUT, bytesConsumed, 0)
            ret == MBEDTLS_ERR_SSL_WANT_WRITE ->
                TlsCodecResult(TlsResult.NEED_WRAP, bytesConsumed, 0)
            ret == MBEDTLS_ERR_SSL_PEER_CLOSE_NOTIFY ->
                TlsCodecResult(TlsResult.CLOSED, bytesConsumed, 0)
            else -> {
                val op = if (isHandshakeComplete) "ssl_read" else "ssl_handshake"
                throw TlsException(
                    "mbedtls_$op failed: ${errorString(ret)}",
                    TlsErrorCategory.PROTOCOL_ERROR,
                    ret.toLong(),
                )
            }
        }
    }

    override fun protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult {
        val cipherPtr = ciphertext.unsafePointer
        bioCtx.send_ptr = (cipherPtr + ciphertext.writerIndex)!!.reinterpret<UByteVar>()
        bioCtx.send_capacity = ciphertext.writableBytes.toULong()
        bioCtx.send_written = 0u

        val plainPtr = plaintext.unsafePointer
        val toWrite = plaintext.readableBytes

        val ret = if (toWrite == 0 && !isHandshakeComplete) {
            mbedtls_ssl_handshake(ssl.ptr)
        } else {
            mbedtls_ssl_write(
                ssl.ptr,
                (plainPtr + plaintext.readerIndex)!!.reinterpret<UByteVar>(),
                toWrite.toULong(),
            )
        }

        val sendWritten = bioCtx.send_written.toInt()
        ciphertext.writerIndex += sendWritten

        // Reset send pointer AND capacity/written: leaving the latter
        // stale lets a later BIO write (e.g. mbedtls_ssl_close_notify
        // during close()) see avail > 0 and memcpy into `null + send_written`,
        // a null-pointer-offset write. Zeroing them forces the WANT_WRITE path.
        bioCtx.send_ptr = null
        bioCtx.send_capacity = 0u
        bioCtx.send_written = 0u

        return when {
            ret >= 0 -> TlsCodecResult(TlsResult.OK, if (ret > 0) ret else 0, sendWritten)
            ret == MBEDTLS_ERR_SSL_WANT_READ ->
                TlsCodecResult(TlsResult.NEED_MORE_INPUT, 0, sendWritten)
            ret == MBEDTLS_ERR_SSL_WANT_WRITE ->
                TlsCodecResult(TlsResult.NEED_WRAP, 0, sendWritten)
            else -> {
                val op = if (isHandshakeComplete) "ssl_write" else "ssl_handshake"
                throw TlsException(
                    "mbedtls_$op failed: ${errorString(ret)}",
                    TlsErrorCategory.PROTOCOL_ERROR,
                    ret.toLong(),
                )
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        mbedtls_ssl_close_notify(ssl.ptr)
        mbedtls_ssl_free(ssl.ptr)
        nativeHeap.free(ssl.rawPtr)
        nativeHeap.free(bioCtx.rawPtr)
        // Release our reference to the shared session — if the
        // factory has already released, this is the last reference
        // and the underlying mbedtls_ssl_config / cert / key are
        // freed exactly once here.
        session.release()
    }

    // --- Internal ---

    private fun checkMbedTls(ret: Int, op: String) {
        if (ret != 0) {
            throw TlsException("$op failed: ${errorString(ret)}", TlsErrorCategory.HANDSHAKE_FAILED, ret.toLong())
        }
    }

    private fun errorString(ret: Int): String =
        keel_mbedtls_strerror(ret)?.toKString() ?: "unknown error ($ret)"
}
