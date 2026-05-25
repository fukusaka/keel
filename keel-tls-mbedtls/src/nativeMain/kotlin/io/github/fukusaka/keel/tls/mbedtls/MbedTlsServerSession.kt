@file:OptIn(ExperimentalForeignApi::class)

package io.github.fukusaka.keel.tls.mbedtls

import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsErrorCategory
import io.github.fukusaka.keel.tls.TlsException
import io.github.fukusaka.keel.tls.asPem
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import mbedtls.MBEDTLS_SSL_IS_CLIENT
import mbedtls.MBEDTLS_SSL_IS_SERVER
import mbedtls.MBEDTLS_SSL_PRESET_DEFAULT
import mbedtls.MBEDTLS_SSL_TRANSPORT_STREAM
import mbedtls.keel_mbedtls_strerror
import mbedtls.mbedtls_pk_context
import mbedtls.mbedtls_pk_free
import mbedtls.mbedtls_pk_init
import mbedtls.mbedtls_pk_parse_key
import mbedtls.mbedtls_ssl_conf_ca_chain
import mbedtls.mbedtls_ssl_conf_own_cert
import mbedtls.mbedtls_ssl_config
import mbedtls.mbedtls_ssl_config_defaults
import mbedtls.mbedtls_ssl_config_free
import mbedtls.mbedtls_ssl_config_init
import mbedtls.mbedtls_x509_crt
import mbedtls.mbedtls_x509_crt_free
import mbedtls.mbedtls_x509_crt_init
import mbedtls.mbedtls_x509_crt_parse

/**
 * Shared, read-after-setup Mbed TLS resources for one
 * ([isServer], [TlsConfig]) pair.
 *
 * Bundles `mbedtls_x509_crt` + `mbedtls_pk_context` +
 * `mbedtls_ssl_config` and parses the PEM cert / key once at
 * construction. Per-connection [MbedTlsCodec] instances created from
 * the same session share these via `mbedtls_ssl_setup(ssl, conf)` —
 * Mbed TLS treats `mbedtls_ssl_config` as read-only after `ssl_setup`,
 * so concurrent use by multiple `mbedtls_ssl_context` instances is
 * safe without locking.
 *
 * **Why this exists**: Mbed TLS 4.x's PSA Crypto subsystem is not
 * thread-safe unless `MBEDTLS_THREADING_C` is enabled at the C build
 * (homebrew / Linux distro packages disable it). With the original
 * per-codec `psa_crypto_init` + cert / key / config init, concurrent
 * `createServerCodec` calls (one per accepted connection on the
 * pipeline-http-epoll multi-worker path) raced in PSA's global key
 * store and crashed the process with
 * `mbedtls_x509_crt_parse: MBEDTLS_ERR_PK_INVALID_PUBKEY (0x3B00)` —
 * see K53 / `MbedTlsConcurrentCodecCreationTest`.
 *
 * **Lifetime**: must outlive every [MbedTlsCodec] derived from it.
 * Owned by [MbedTlsCodecFactory]; freed in [close]. Calling [close]
 * while live codecs reference this session leads to use-after-free.
 */
internal class MbedTlsServerSession(
    isServer: Boolean,
    config: TlsConfig,
) {
    val srvcert = nativeHeap.alloc<mbedtls_x509_crt>()
    val pkey = nativeHeap.alloc<mbedtls_pk_context>()
    val conf = nativeHeap.alloc<mbedtls_ssl_config>()

    init {
        mbedtls_x509_crt_init(srvcert.ptr)
        mbedtls_pk_init(pkey.ptr)

        val certSource = config.certificates
        if (certSource is TlsCertificateSource.Pem || certSource is TlsCertificateSource.Der) {
            val pem = certSource.asPem()
            parsePemCert(pem.certificatePem)
            parsePemKey(pem.privateKeyPem)
        }

        mbedtls_ssl_config_init(conf.ptr)
        val endpoint = if (isServer) MBEDTLS_SSL_IS_SERVER else MBEDTLS_SSL_IS_CLIENT
        var ret = mbedtls_ssl_config_defaults(
            conf.ptr, endpoint, MBEDTLS_SSL_TRANSPORT_STREAM, MBEDTLS_SSL_PRESET_DEFAULT,
        )
        check(ret == 0) { "ssl_config_defaults failed: ${errorString(ret)}" }

        mbedtls_ssl_conf_ca_chain(conf.ptr, srvcert.ptr, null)
        ret = mbedtls_ssl_conf_own_cert(conf.ptr, srvcert.ptr, pkey.ptr)
        check(ret == 0) { "ssl_conf_own_cert failed: ${errorString(ret)}" }
    }

    fun close() {
        mbedtls_ssl_config_free(conf.ptr)
        mbedtls_x509_crt_free(srvcert.ptr)
        mbedtls_pk_free(pkey.ptr)
        nativeHeap.free(conf.rawPtr)
        nativeHeap.free(srvcert.rawPtr)
        nativeHeap.free(pkey.rawPtr)
    }

    private fun parsePemCert(pem: String) {
        val bytes = pem.encodeToByteArray() + byteArrayOf(0)
        val ret = bytes.usePinned { pinned ->
            mbedtls_x509_crt_parse(srvcert.ptr, pinned.addressOf(0).reinterpret<UByteVar>(), bytes.size.toULong())
        }
        if (ret != 0) {
            throw TlsException(
                "x509_crt_parse failed: ${errorString(ret)}",
                TlsErrorCategory.HANDSHAKE_FAILED,
                ret.toLong(),
            )
        }
    }

    private fun parsePemKey(pem: String) {
        val bytes = pem.encodeToByteArray() + byteArrayOf(0)
        val ret = bytes.usePinned { pinned ->
            mbedtls_pk_parse_key(pkey.ptr, pinned.addressOf(0).reinterpret<UByteVar>(), bytes.size.toULong(), null, 0u)
        }
        if (ret != 0) {
            throw TlsException(
                "pk_parse_key failed: ${errorString(ret)}",
                TlsErrorCategory.HANDSHAKE_FAILED,
                ret.toLong(),
            )
        }
    }

    private fun errorString(ret: Int): String =
        keel_mbedtls_strerror(ret)?.toKString() ?: "unknown error ($ret)"
}
