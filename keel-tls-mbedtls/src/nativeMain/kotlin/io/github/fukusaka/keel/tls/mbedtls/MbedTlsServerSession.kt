@file:OptIn(ExperimentalForeignApi::class)

package io.github.fukusaka.keel.tls.mbedtls

import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsErrorCategory
import io.github.fukusaka.keel.tls.TlsException
import io.github.fukusaka.keel.tls.TlsTrustSource
import io.github.fukusaka.keel.tls.TlsVerifyMode
import io.github.fukusaka.keel.tls.TlsVersion
import io.github.fukusaka.keel.tls.asPem
import kotlin.concurrent.AtomicInt
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
import mbedtls.MBEDTLS_SSL_VERIFY_NONE
import mbedtls.MBEDTLS_SSL_VERIFY_OPTIONAL
import mbedtls.MBEDTLS_SSL_VERIFY_REQUIRED
import mbedtls.MBEDTLS_SSL_VERSION_TLS1_2
import mbedtls.MBEDTLS_SSL_VERSION_TLS1_3
import mbedtls.keel_mbedtls_strerror
import mbedtls.mbedtls_pk_context
import mbedtls.mbedtls_pk_free
import mbedtls.mbedtls_pk_init
import mbedtls.mbedtls_pk_parse_key
import mbedtls.mbedtls_ssl_conf_authmode
import mbedtls.mbedtls_ssl_conf_ca_chain
import mbedtls.mbedtls_ssl_conf_max_tls_version
import mbedtls.mbedtls_ssl_conf_min_tls_version
import mbedtls.mbedtls_ssl_conf_own_cert
import mbedtls.mbedtls_ssl_config
import mbedtls.mbedtls_ssl_protocol_version
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
 * **Lifetime**: reference-counted. The constructor seats one
 * implicit reference for [MbedTlsCodecFactory]'s cache; every
 * derived [MbedTlsCodec] adds one via [retain] at construction
 * and removes it via [release] at codec close. The session's
 * underlying Mbed TLS structs (`mbedtls_ssl_config`, `mbedtls_x509_crt`,
 * `mbedtls_pk_context`) are freed when the count reaches zero —
 * whichever party (factory close vs last in-flight codec close)
 * is the last to release wins, with no UAF either way.
 *
 * The refcount is the production-grade replacement for the
 * pre-existing "caller must drain live codecs before
 * factory.close()" invariant: now any ordering is safe.
 */
internal class MbedTlsServerSession(
    isServer: Boolean,
    config: TlsConfig,
) {
    val srvcert = nativeHeap.alloc<mbedtls_x509_crt>()
    val pkey = nativeHeap.alloc<mbedtls_pk_context>()
    val conf = nativeHeap.alloc<mbedtls_ssl_config>()

    // The trust-anchor CA chain, allocated only when [TlsConfig.trustAnchors]
    // is a `Pem` source (peer-certificate verification). Freed in [release].
    private var cacert: mbedtls_x509_crt? = null

    // Starts at 1 for MbedTlsCodecFactory's cache reference; each
    // derived MbedTlsCodec adds +1 on retain() in its constructor
    // and -1 on release() at close.
    private val refCount = AtomicInt(1)

    init {
        mbedtls_x509_crt_init(srvcert.ptr)
        mbedtls_pk_init(pkey.ptr)

        val certSource = config.certificates
        if (certSource is TlsCertificateSource.Pem || certSource is TlsCertificateSource.Der) {
            val pem = certSource.asPem()
            parsePemCert(srvcert, pem.certificatePem)
            parsePemKey(pem.privateKeyPem)
        }

        mbedtls_ssl_config_init(conf.ptr)
        val endpoint = if (isServer) MBEDTLS_SSL_IS_SERVER else MBEDTLS_SSL_IS_CLIENT
        checkMbedTls(
            mbedtls_ssl_config_defaults(
                conf.ptr, endpoint, MBEDTLS_SSL_TRANSPORT_STREAM, MBEDTLS_SSL_PRESET_DEFAULT,
            ),
            "ssl_config_defaults",
        )

        checkMbedTls(
            mbedtls_ssl_conf_own_cert(conf.ptr, srvcert.ptr, pkey.ptr),
            "ssl_conf_own_cert",
        )

        // Trust anchors used to verify the peer certificate. Only `Pem`
        // installs a CA chain; `InsecureTrustAll` disables verification
        // (below), and `SystemDefault` has no portable Mbed TLS equivalent.
        when (val trust = config.trustAnchors) {
            is TlsTrustSource.Pem -> {
                val ca = nativeHeap.alloc<mbedtls_x509_crt>().also { mbedtls_x509_crt_init(it.ptr) }
                cacert = ca
                parsePemCert(ca, trust.caPem)
                mbedtls_ssl_conf_ca_chain(conf.ptr, ca.ptr, null)
            }
            is TlsTrustSource.SystemDefault -> throw TlsException(
                "TlsTrustSource.SystemDefault is not supported by the Mbed TLS backend " +
                    "(no portable system trust store); use TlsTrustSource.Pem",
                TlsErrorCategory.HANDSHAKE_FAILED,
            )
            is TlsTrustSource.InsecureTrustAll, null -> Unit // no CA chain
        }

        // Apply the peer-verification mode. Without this, Mbed TLS leaves a
        // server at its default `VERIFY_NONE` — it would never request a
        // client certificate, so `REQUIRED` (mutual TLS) silently accepted a
        // cert-less client. `REQUIRED` aborts when the peer presents no
        // certificate; `OPTIONAL` verifies one if presented. `InsecureTrustAll`
        // overrides to `VERIFY_NONE` (trust anything), matching OpenSSL.
        val authmode = if (config.trustAnchors is TlsTrustSource.InsecureTrustAll) {
            MBEDTLS_SSL_VERIFY_NONE
        } else {
            mbedtlsAuthMode(config.verifyMode, isServer)
        }
        mbedtls_ssl_conf_authmode(conf.ptr, authmode)

        // Pin the negotiable protocol version range. Both bounds are set
        // explicitly: the floor (minVersion, default TLS 1.2) so anything
        // below TLS 1.2 is never negotiable, and the ceiling (maxVersion,
        // or TLS 1.3 when null) so keel never negotiates a version it does
        // not enumerate. Range validated by TlsConfig.
        mbedtls_ssl_conf_min_tls_version(conf.ptr, mbedtlsVersion(config.minVersion))
        mbedtls_ssl_conf_max_tls_version(conf.ptr, mbedtlsVersion(config.maxVersion ?: TlsVersion.TLS1_3))
    }

    private fun mbedtlsVersion(version: TlsVersion): mbedtls_ssl_protocol_version = when (version) {
        TlsVersion.TLS1_2 -> MBEDTLS_SSL_VERSION_TLS1_2
        TlsVersion.TLS1_3 -> MBEDTLS_SSL_VERSION_TLS1_3
    }

    /**
     * Maps keel's [TlsVerifyMode] to the Mbed TLS `MBEDTLS_SSL_VERIFY_*`
     * authmode, role-aware: `PEER` means "request a client cert but accept
     * its absence" on a **server** (`OPTIONAL`), but on a **client** there
     * is no useful "verify yet continue on failure" mode — a client that
     * verifies the server must abort on failure, so `PEER` maps to
     * `REQUIRED` there (matching OpenSSL / AWS-LC, where client-side
     * `SSL_VERIFY_PEER` aborts on a bad server cert). `REQUIRED` is
     * `REQUIRED` for both roles; `NONE` disables verification.
     */
    private fun mbedtlsAuthMode(mode: TlsVerifyMode, isServer: Boolean): Int = when (mode) {
        TlsVerifyMode.NONE -> MBEDTLS_SSL_VERIFY_NONE
        TlsVerifyMode.PEER -> if (isServer) MBEDTLS_SSL_VERIFY_OPTIONAL else MBEDTLS_SSL_VERIFY_REQUIRED
        TlsVerifyMode.REQUIRED -> MBEDTLS_SSL_VERIFY_REQUIRED
    }

    /**
     * Add one reference unconditionally. Caller must already hold
     * at least one reference (typically inherited from a recent
     * [tryRetain]) — see [tryRetain] for the safe entry point used
     * by [MbedTlsCodecFactory] on a session it just looked up in
     * an unlocked snapshot.
     */
    fun retain() {
        val updated = refCount.incrementAndGet()
        check(updated > 1) { "retain() on already-freed MbedTlsServerSession" }
    }

    /**
     * Atomically increment the reference count **only if it is
     * currently > 0** (i.e. the session has not yet been freed).
     * Returns true on success, false if the session has already
     * dropped to zero and its mbedtls structs are gone.
     *
     * This is the safe entry point for any code path that holds a
     * reference to the Kotlin session object **without** also
     * holding the construct mutex: a concurrent [release] may have
     * dropped the count to zero between the look-up and the
     * retain. The classic `if (count > 0) count++` is racy; the
     * CAS loop below makes the check + increment atomic.
     */
    fun tryRetain(): Boolean {
        while (true) {
            val current = refCount.value
            if (current == 0) return false
            if (refCount.compareAndSet(current, current + 1)) return true
        }
    }

    /**
     * Drop one reference. When the count reaches zero, the
     * underlying Mbed TLS structs are freed exactly once. Safe to
     * call from any thread.
     */
    fun release() {
        val remaining = refCount.decrementAndGet()
        check(remaining >= 0) { "release() under-balanced MbedTlsServerSession" }
        if (remaining == 0) {
            mbedtls_ssl_config_free(conf.ptr)
            mbedtls_x509_crt_free(srvcert.ptr)
            mbedtls_pk_free(pkey.ptr)
            cacert?.let {
                mbedtls_x509_crt_free(it.ptr)
                nativeHeap.free(it.rawPtr)
            }
            nativeHeap.free(conf.rawPtr)
            nativeHeap.free(srvcert.rawPtr)
            nativeHeap.free(pkey.rawPtr)
        }
    }

    private fun parsePemCert(target: mbedtls_x509_crt, pem: String) {
        val bytes = pem.encodeToByteArray() + byteArrayOf(0)
        val ret = bytes.usePinned { pinned ->
            mbedtls_x509_crt_parse(target.ptr, pinned.addressOf(0).reinterpret<UByteVar>(), bytes.size.toULong())
        }
        checkMbedTls(ret, "x509_crt_parse")
    }

    private fun parsePemKey(pem: String) {
        val bytes = pem.encodeToByteArray() + byteArrayOf(0)
        val ret = bytes.usePinned { pinned ->
            mbedtls_pk_parse_key(pkey.ptr, pinned.addressOf(0).reinterpret<UByteVar>(), bytes.size.toULong(), null, 0u)
        }
        checkMbedTls(ret, "pk_parse_key")
    }

    private fun checkMbedTls(ret: Int, op: String) {
        if (ret != 0) {
            throw TlsException(
                "$op failed: ${errorString(ret)}",
                TlsErrorCategory.HANDSHAKE_FAILED,
                ret.toLong(),
            )
        }
    }

    private fun errorString(ret: Int): String =
        keel_mbedtls_strerror(ret)?.toKString() ?: "unknown error ($ret)"
}
