@file:OptIn(ExperimentalForeignApi::class)

package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.tls.Pkcs8KeyUnwrapper
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import nwconnection.keel_nw_create_private_key
import nwconnection.keel_nw_create_tls_tcp_params
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFRelease
import platform.Network.nw_parameters_t
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecIdentityCreate
import platform.Security.sec_identity_create

/**
 * Creates NWConnection TLS parameters from DER-encoded certificate and private key.
 *
 * Converts DER cert + inner key (PKCS#1/SEC1, not PKCS#8) into a
 * [SecIdentity][platform.Security.SecIdentityRef] via the keychain-free
 * [SecIdentityCreate] API (macOS 10.12+), then wraps it in
 * [nw_parameters_t] for listener-level TLS.
 *
 * Flow:
 * ```
 * cert DER → SecCertificateCreateWithData   → SecCertificateRef
 * key  DER → keel_nw_create_private_key     → SecKeyRef
 *                                             ↓
 *         SecIdentityCreate(cert, key)       → SecIdentityRef
 *         sec_identity_create                → sec_identity_t
 *         keel_nw_create_tls_tcp_params      → nw_parameters_t
 * ```
 */
internal object NwTlsParams {

    /**
     * Creates TLS-enabled NWConnection parameters.
     *
     * @param certDer DER-encoded X.509 certificate.
     * @param innerKeyDer DER-encoded private key in PKCS#1 (RSA) or SEC 1 (EC) format.
     *   Must NOT be PKCS#8 — use [Pkcs8KeyUnwrapper.unwrap] first if needed.
     * @param keyAlgorithm Key algorithm for [SecKeyCreateWithData] attributes.
     * @return NWConnection parameters with TLS identity configured.
     * @throws IllegalStateException if Security framework rejects the cert or key.
     */
    fun createTlsParameters(
        certDer: ByteArray,
        innerKeyDer: ByteArray,
        keyAlgorithm: Pkcs8KeyUnwrapper.KeyAlgorithm,
    ): nw_parameters_t {
        val cert = createCertificate(certDer)
            ?: error("SecCertificateCreateWithData failed — invalid certificate DER")
        try {
            val keyType = when (keyAlgorithm) {
                Pkcs8KeyUnwrapper.KeyAlgorithm.EC -> 1
                else -> 0
            }
            val key = innerKeyDer.usePinned { pinned ->
                keel_nw_create_private_key(
                    pinned.addressOf(0), innerKeyDer.size.toUInt(), keyType,
                )
            } ?: run {
                CFRelease(cert)
                error("SecKeyCreateWithData failed — invalid private key DER or wrong key type")
            }
            try {
                val identity = SecIdentityCreate(null, cert, key)
                    ?: run {
                        CFRelease(key)
                        CFRelease(cert)
                        error("SecIdentityCreate failed — cert/key pair mismatch")
                    }
                try {
                    val secIdentity = sec_identity_create(identity)
                        ?: run {
                            CFRelease(identity)
                            CFRelease(key)
                            CFRelease(cert)
                            error("sec_identity_create failed")
                        }
                    return keel_nw_create_tls_tcp_params(secIdentity)
                        ?: error("keel_nw_create_tls_tcp_params failed")
                } finally {
                    CFRelease(identity)
                }
            } finally {
                CFRelease(key)
            }
        } finally {
            CFRelease(cert)
        }
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
}
