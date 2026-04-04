@file:OptIn(ExperimentalForeignApi::class)

package io.github.fukusaka.keel.tls.mbedtls

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.TlsCodecResult
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsErrorCategory
import io.github.fukusaka.keel.tls.TlsException
import io.github.fukusaka.keel.tls.TlsResult
import io.github.fukusaka.keel.tls.TlsCertificateSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import mbedtls.MBEDTLS_ERR_SSL_PEER_CLOSE_NOTIFY
import mbedtls.MBEDTLS_ERR_SSL_WANT_READ
import mbedtls.MBEDTLS_ERR_SSL_WANT_WRITE
import mbedtls.MBEDTLS_SSL_IS_CLIENT
import mbedtls.MBEDTLS_SSL_IS_SERVER
import mbedtls.MBEDTLS_SSL_PRESET_DEFAULT
import mbedtls.MBEDTLS_SSL_TRANSPORT_STREAM
import mbedtls.keel_mbedtls_bio_ctx
import mbedtls.keel_mbedtls_bio_setup
import mbedtls.keel_mbedtls_strerror
import mbedtls.mbedtls_pk_context
import mbedtls.mbedtls_pk_free
import mbedtls.mbedtls_pk_init
import mbedtls.mbedtls_pk_parse_key
import mbedtls.mbedtls_ssl_close_notify
import mbedtls.mbedtls_ssl_config
import mbedtls.mbedtls_ssl_config_defaults
import mbedtls.mbedtls_ssl_config_free
import mbedtls.mbedtls_ssl_config_init
import mbedtls.mbedtls_ssl_conf_ca_chain
import mbedtls.mbedtls_ssl_conf_own_cert
import mbedtls.mbedtls_ssl_context
import mbedtls.mbedtls_ssl_free
import mbedtls.mbedtls_ssl_get_alpn_protocol
import mbedtls.mbedtls_ssl_handshake
import mbedtls.mbedtls_ssl_init
import mbedtls.mbedtls_ssl_is_handshake_over
import mbedtls.mbedtls_ssl_read
import mbedtls.mbedtls_ssl_setup
import mbedtls.mbedtls_ssl_write
import mbedtls.mbedtls_x509_crt
import mbedtls.mbedtls_x509_crt_free
import mbedtls.mbedtls_x509_crt_init
import mbedtls.mbedtls_x509_crt_parse
import mbedtls.psa_crypto_init

/**
 * [TlsCodec] implementation backed by Mbed TLS 4.x.
 *
 * Uses a pointer-based BIO adapter: recv/send callbacks read from and
 * write to caller-owned IoBuf memory directly — no intermediate buffer
 * copies beyond the fundamental AEAD encrypt/decrypt.
 *
 * **Lifecycle**: call [close] to free all Mbed TLS resources
 * (ssl_context, ssl_config, x509_crt, pk_context, bio_ctx).
 */
class MbedTlsCodec internal constructor(
    private val isServer: Boolean,
    config: TlsConfig,
) : TlsCodec {

    // Mbed TLS structs — heap-allocated to survive beyond memScoped.
    private val ssl = nativeHeap.alloc<mbedtls_ssl_context>()
    private val conf = nativeHeap.alloc<mbedtls_ssl_config>()
    private val srvcert = nativeHeap.alloc<mbedtls_x509_crt>()
    private val pkey = nativeHeap.alloc<mbedtls_pk_context>()
    private val bioCtx = nativeHeap.alloc<keel_mbedtls_bio_ctx>()

    private var closed = false

    init {
        // PSA Crypto init (Mbed TLS 4.x: replaces entropy/ctr_drbg).
        val psaRet = psa_crypto_init().toInt()
        check(psaRet == 0) { "psa_crypto_init failed: $psaRet" }

        // Certificate and key.
        mbedtls_x509_crt_init(srvcert.ptr)
        mbedtls_pk_init(pkey.ptr)

        val certSource = config.certificates
        if (certSource is TlsCertificateSource.Pem) {
            parsePemCert(certSource.certificatePem)
            parsePemKey(certSource.privateKeyPem)
        }

        // SSL config.
        mbedtls_ssl_config_init(conf.ptr)
        val endpoint = if (isServer) MBEDTLS_SSL_IS_SERVER else MBEDTLS_SSL_IS_CLIENT
        var ret = mbedtls_ssl_config_defaults(
            conf.ptr, endpoint, MBEDTLS_SSL_TRANSPORT_STREAM, MBEDTLS_SSL_PRESET_DEFAULT,
        )
        checkMbedTls(ret, "ssl_config_defaults")

        mbedtls_ssl_conf_ca_chain(conf.ptr, srvcert.ptr, null)
        ret = mbedtls_ssl_conf_own_cert(conf.ptr, srvcert.ptr, pkey.ptr)
        checkMbedTls(ret, "ssl_conf_own_cert")

        // SSL context.
        mbedtls_ssl_init(ssl.ptr)
        ret = mbedtls_ssl_setup(ssl.ptr, conf.ptr)
        checkMbedTls(ret, "ssl_setup")

        // Register pointer-based BIO.
        keel_mbedtls_bio_setup(ssl.ptr, bioCtx.ptr)
    }

    // --- TlsCodec ---

    override val isHandshakeComplete: Boolean
        get() = mbedtls_ssl_is_handshake_over(ssl.ptr) == 1

    override val negotiatedProtocol: String?
        get() = mbedtls_ssl_get_alpn_protocol(ssl.ptr)?.toKString()

    override val peerCertificates: List<ByteArray>
        get() = emptyList() // Phase 9b: peer cert extraction deferred.

    override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult {
        // Set recv pointer to ciphertext IoBuf.
        val cipherPtr = ciphertext.unsafePointer
        bioCtx.recv_ptr = (cipherPtr + ciphertext.readerIndex)!!.reinterpret<UByteVar>()
        bioCtx.recv_remaining = ciphertext.readableBytes.toULong()

        // Do NOT set send pointer during unprotect. Handshake responses
        // (ServerHello, Certificate, etc.) must go to the ciphertext output
        // of protect(), not the plaintext output here. If Mbed TLS needs
        // to send during ssl_handshake/ssl_read, send_cb returns WANT_WRITE,
        // causing NEED_WRAP — the caller then invokes protect() to flush.
        bioCtx.send_ptr = null
        bioCtx.send_capacity = 0u
        bioCtx.send_written = 0u

        val plainPtr = plaintext.unsafePointer

        // During handshake, use ssl_handshake explicitly to consume incoming
        // handshake records. After handshake completes, use ssl_read for
        // application data decryption.
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

        // Reset recv pointers — only valid during this call.
        // Both must be cleared: recv_remaining > 0 with null recv_ptr
        // would cause null dereference in the BIO recv callback.
        bioCtx.recv_ptr = null
        bioCtx.recv_remaining = 0u

        return when {
            ret > 0 -> {
                // ssl_read returned application data.
                plaintext.writerIndex += ret
                TlsCodecResult(TlsResult.OK, bytesConsumed, ret)
            }
            ret == 0 && isHandshakeComplete -> {
                // ssl_handshake completed (returned 0). No plaintext produced yet;
                // application data will arrive in subsequent onRead calls.
                TlsCodecResult(TlsResult.OK, bytesConsumed, 0)
            }
            ret == MBEDTLS_ERR_SSL_WANT_READ ->
                TlsCodecResult(TlsResult.NEED_MORE_INPUT, bytesConsumed, 0)
            ret == MBEDTLS_ERR_SSL_WANT_WRITE ->
                TlsCodecResult(TlsResult.NEED_WRAP, bytesConsumed, 0)
            ret == MBEDTLS_ERR_SSL_PEER_CLOSE_NOTIFY ->
                TlsCodecResult(TlsResult.CLOSED, bytesConsumed, 0)
            else -> throw TlsException(
                "mbedtls_ssl_read failed: ${errorString(ret)}",
                TlsErrorCategory.PROTOCOL_ERROR,
                ret.toLong(),
            )
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

        // Reset pointer.
        bioCtx.send_ptr = null

        return when {
            ret >= 0 -> TlsCodecResult(TlsResult.OK, if (ret > 0) ret else 0, sendWritten)
            ret == MBEDTLS_ERR_SSL_WANT_READ ->
                TlsCodecResult(TlsResult.NEED_MORE_INPUT, 0, sendWritten)
            ret == MBEDTLS_ERR_SSL_WANT_WRITE ->
                TlsCodecResult(TlsResult.NEED_WRAP, 0, sendWritten)
            else -> throw TlsException(
                "mbedtls_ssl_write failed: ${errorString(ret)}",
                TlsErrorCategory.PROTOCOL_ERROR,
                ret.toLong(),
            )
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        mbedtls_ssl_close_notify(ssl.ptr)
        mbedtls_ssl_free(ssl.ptr)
        mbedtls_ssl_config_free(conf.ptr)
        mbedtls_x509_crt_free(srvcert.ptr)
        mbedtls_pk_free(pkey.ptr)
        nativeHeap.free(ssl.rawPtr)
        nativeHeap.free(conf.rawPtr)
        nativeHeap.free(srvcert.rawPtr)
        nativeHeap.free(pkey.rawPtr)
        nativeHeap.free(bioCtx.rawPtr)
    }

    // --- Internal ---

    private fun parsePemCert(pem: String) {
        val bytes = pem.encodeToByteArray() + 0 // null-terminated
        val ret = bytes.usePinned { pinned ->
            mbedtls_x509_crt_parse(srvcert.ptr, pinned.addressOf(0).reinterpret(), bytes.size.toULong())
        }
        checkMbedTls(ret, "x509_crt_parse")
    }

    private fun parsePemKey(pem: String) {
        val bytes = pem.encodeToByteArray() + 0 // null-terminated
        val ret = bytes.usePinned { pinned ->
            mbedtls_pk_parse_key(pkey.ptr, pinned.addressOf(0).reinterpret(), bytes.size.toULong(), null, 0u)
        }
        checkMbedTls(ret, "pk_parse_key")
    }

    private fun checkMbedTls(ret: Int, op: String) {
        if (ret != 0) {
            throw TlsException("$op failed: ${errorString(ret)}", TlsErrorCategory.HANDSHAKE_FAILED, ret.toLong())
        }
    }

    private fun errorString(ret: Int): String =
        keel_mbedtls_strerror(ret)?.toKString() ?: "unknown error ($ret)"
}
