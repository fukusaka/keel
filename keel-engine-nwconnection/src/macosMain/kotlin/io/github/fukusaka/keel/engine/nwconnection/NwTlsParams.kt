@file:OptIn(ExperimentalForeignApi::class)

package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.tls.PemDerConverter
import io.github.fukusaka.keel.tls.Pkcs8KeyUnwrapper
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsTrustSource
import io.github.fukusaka.keel.tls.TlsVerifyMode
import io.github.fukusaka.keel.tls.TlsVersion
import io.github.fukusaka.keel.tls.asDer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import nwconnection.keel_nw_create_private_key
import nwconnection.keel_nw_create_tls_tcp_params_full
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFRelease
import platform.Network.nw_parameters_t
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecIdentityCreate
import platform.Security.sec_identity_create

/**
 * Creates NWConnection TLS parameters honouring the full [TlsConfig] on
 * the server side.
 *
 * Converts DER cert + inner key (PKCS#1/SEC1, not PKCS#8) into a
 * [SecIdentity][platform.Security.SecIdentityRef] via the keychain-free
 * [SecIdentityCreate] API (macOS 10.12+), then hands identity + the rest
 * of `TlsConfig` (min/max version, ALPN, mTLS mode + pinned CA anchors)
 * to the `keel_nw_create_tls_tcp_params_full` C wrapper which sets them
 * inside the `nw_parameters_create_secure_tcp` TLS configure block via
 * `sec_protocol_options_*`.
 *
 * Flow:
 * ```
 * cert DER → SecCertificateCreateWithData   → SecCertificateRef
 * key  DER → keel_nw_create_private_key     → SecKeyRef
 *                                             ↓
 *         SecIdentityCreate(cert, key)       → SecIdentityRef
 *         sec_identity_create                → sec_identity_t
 *         keel_nw_create_tls_tcp_params_full → nw_parameters_t
 *                (identity + min/max version + ALPN + peer-auth + anchors)
 * ```
 */
internal object NwTlsParams {

    /**
     * Creates TLS-enabled NWConnection parameters honouring every
     * server-relevant axis of [config]:
     *
     *   * [TlsConfig.certificates]   → local identity (required)
     *   * [TlsConfig.minVersion] / [TlsConfig.maxVersion] → TLS protocol range
     *     via `sec_protocol_options_set_min_tls_protocol_version` etc.
     *   * [TlsConfig.alpnProtocols]  → `sec_protocol_options_add_tls_application_protocol`
     *   * [TlsConfig.verifyMode]     → `sec_protocol_options_set_peer_authentication_required`
     *     - [TlsVerifyMode.PEER] is mapped to `REQUIRED`
     *       (NW's `sec_protocol_options_set_peer_authentication_optional` is
     *       API_UNAVAILABLE on macOS / iOS, so no true "want-but-don't-require"
     *       middle ground exists — safer to require than to silently downgrade)
     *   * [TlsConfig.trustAnchors]   → verify block that pins client cert
     *     verification to the given PEM anchor list (only when peer auth is on)
     *
     * @throws IllegalStateException if the Security framework rejects the
     *   cert or key, or if the required identity is missing.
     */
    fun createTlsParameters(
        config: TlsConfig,
        socketOptions: SocketOptions = SocketOptions.DEFAULT,
    ): nw_parameters_t {
        val certs = requireNotNull(config.certificates) {
            "NWConnection listener-level TLS requires certificates"
        }.asDer()

        val innerKeyDer = certs.privateKey
        val (innerKey, keyAlgorithm) = if (Pkcs8KeyUnwrapper.isPkcs8(innerKeyDer)) {
            Pkcs8KeyUnwrapper.unwrap(innerKeyDer)
        } else {
            Pkcs8KeyUnwrapper.UnwrapResult(innerKeyDer, Pkcs8KeyUnwrapper.KeyAlgorithm.UNKNOWN)
        }

        val secIdentity = buildSecIdentity(certs.certificate, innerKey, keyAlgorithm)

        val tlsMin = tlsVersionOrdinal(config.minVersion)
        val tlsMax = config.maxVersion?.let { tlsVersionOrdinal(it) } ?: 0
        val requirePeerAuth = config.verifyMode != TlsVerifyMode.NONE

        val alpn = config.alpnProtocols.orEmpty()
        val anchors = trustAnchorDerList(config.trustAnchors, requirePeerAuth)

        return memScoped {
            // ALPN list — array of const char*. Each cstr is allocated in
            // this memScope, so it lives long enough for the C wrapper.
            val alpnPtrs = allocArray<CPointerVar<kotlinx.cinterop.ByteVar>>(alpn.size.coerceAtLeast(1))
            alpn.forEachIndexed { i, s -> alpnPtrs[i] = s.cstr.getPointer(this) }

            // CA anchor DER list — array of pointer + array of length.
            // The C wrapper copies each blob into a SecCertificateRef inside
            // the TLS configure block, so this scoped memory is only alive
            // for the duration of the call — no lifetime issue after return.
            val caCount = anchors.size
            val caPtrs = allocArray<CPointerVar<UByteVar>>(caCount.coerceAtLeast(1))
            val caLens = allocArray<UIntVar>(caCount.coerceAtLeast(1))
            anchors.forEachIndexed { i, bytes ->
                val bufPtr = allocArray<UByteVar>(bytes.size)
                bytes.forEachIndexed { j, b -> bufPtr[j] = b.toUByte() }
                caPtrs[i] = bufPtr
                caLens[i] = bytes.size.toUInt()
            }

            keel_nw_create_tls_tcp_params_full(
                secIdentity,
                tlsMin,
                tlsMax,
                if (requirePeerAuth) 1 else 0,
                if (alpn.isEmpty()) null else alpnPtrs,
                alpn.size,
                if (caCount == 0) null else caPtrs,
                if (caCount == 0) null else caLens,
                caCount,
                socketOptions.toNwNoDelayFlag(),
                socketOptions.toNwKeepAliveFlag(),
            ) ?: error("keel_nw_create_tls_tcp_params_full failed")
        }
    }

    private fun buildSecIdentity(
        certDer: ByteArray,
        innerKeyDer: ByteArray,
        keyAlgorithm: Pkcs8KeyUnwrapper.KeyAlgorithm,
    ) = run {
        val cert = createCertificate(certDer)
            ?: error("SecCertificateCreateWithData failed — invalid certificate DER")

        val keyType = when (keyAlgorithm) {
            Pkcs8KeyUnwrapper.KeyAlgorithm.EC -> 1
            else -> 0
        }
        val key = innerKeyDer.usePinned { pinned ->
            keel_nw_create_private_key(
                pinned.addressOf(0), innerKeyDer.size.toUInt(), keyType,
            )
        }
        if (key == null) {
            CFRelease(cert)
            error("SecKeyCreateWithData failed — invalid private key DER or wrong key type")
        }

        val identity = SecIdentityCreate(null, cert, key)
        CFRelease(key)
        CFRelease(cert)
        checkNotNull(identity) { "SecIdentityCreate failed — cert/key pair mismatch" }

        val secIdentity = sec_identity_create(identity)
        CFRelease(identity)
        checkNotNull(secIdentity) { "sec_identity_create failed" }
        secIdentity
    }

    private fun createCertificate(certDer: ByteArray) = certDer.usePinned { pinned ->
        val cfData = CFDataCreate(
            null, pinned.addressOf(0).reinterpret<UByteVar>(), certDer.size.toLong(),
        ) ?: return@usePinned null
        try {
            SecCertificateCreateWithData(null, cfData)
        } finally {
            CFRelease(cfData)
        }
    }

    private fun tlsVersionOrdinal(version: TlsVersion): Int = when (version) {
        // Values from Security.framework's SecProtocolTypes.h
        // `tls_protocol_version_t`.
        TlsVersion.TLS1_2 -> 0x0303
        TlsVersion.TLS1_3 -> 0x0304
    }

    /**
     * Extracts pinned-CA DER bytes from [TlsConfig.trustAnchors] when the
     * server actually needs them — i.e. when peer authentication is on and
     * the trust source is a [TlsTrustSource.Pem] list. Empty otherwise:
     *
     *   * `null` / [TlsTrustSource.SystemDefault] → let NW fall back to the
     *     system trust store (Apple default), matching keel's other backends
     *     when `trustAnchors = SystemDefault`.
     *   * [TlsTrustSource.InsecureTrustAll] → NW does not offer an "accept
     *     any" verify block API_UNAVAILABLE surface, so it is treated the
     *     same as SystemDefault here; production code should use a real
     *     anchor list or `verifyMode = NONE`.
     */
    private fun trustAnchorDerList(source: TlsTrustSource?, requirePeerAuth: Boolean): List<ByteArray> {
        if (!requirePeerAuth) return emptyList()
        return when (source) {
            null, TlsTrustSource.SystemDefault, TlsTrustSource.InsecureTrustAll -> emptyList()
            is TlsTrustSource.Pem -> splitPemCertificates(source.caPem).map(PemDerConverter::pemToDer)
        }
    }

    /**
     * Splits a PEM bundle containing one or more `-----BEGIN CERTIFICATE-----`
     * blocks into a list of single-cert PEM strings. Preserves each block's
     * BEGIN/END headers so `PemDerConverter.pemToDer` can decode it directly.
     *
     * Whitespace / non-cert lines outside blocks are ignored. Returns an
     * empty list when no blocks are found (the caller then treats it the
     * same as SystemDefault).
     */
    private fun splitPemCertificates(pem: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inBlock = false
        pem.lineSequence().forEach { line ->
            when {
                line.startsWith("-----BEGIN CERTIFICATE-----") -> {
                    inBlock = true
                    current.clear()
                    current.append(line).append('\n')
                }
                line.startsWith("-----END CERTIFICATE-----") -> {
                    if (inBlock) {
                        current.append(line).append('\n')
                        result.add(current.toString())
                        current.clear()
                    }
                    inBlock = false
                }
                inBlock -> current.append(line).append('\n')
            }
        }
        return result
    }
}
