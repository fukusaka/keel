package io.github.fukusaka.keel.tls

/**
 * Source of a certificate + private key pair for TLS.
 *
 * Platform implementations convert these into native certificate objects:
 * - Native (Mbed TLS, OpenSSL): PEM/DER byte arrays
 * - JVM (JSSE): PEM → in-memory KeyStore, or [KeyStoreFile] directly
 * - macOS (NWConnection): [SystemKeychain] → SecIdentity
 * - JS (Node.js): PEM strings passed to `tls.createServer`
 *
 * Each variant carries the minimum information needed by its target platform.
 * The TLS engine implementation is responsible for converting a [TlsCertificateSource]
 * into the platform-native representation (e.g. `mbedtls_x509_crt`, `SSL_CTX`, `KeyStore`).
 */
sealed interface TlsCertificateSource {

    /** PEM-encoded certificate chain and private key as strings. */
    class Pem(
        val certificatePem: String,
        val privateKeyPem: String,
    ) : TlsCertificateSource

    /** DER-encoded certificate and private key as byte arrays. */
    class Der(
        val certificate: ByteArray,
        val privateKey: ByteArray,
    ) : TlsCertificateSource

    /**
     * PKCS12 or JKS KeyStore file on disk (JVM only).
     *
     * Other platforms should use [Pem] or [Der] instead.
     */
    class KeyStoreFile(
        val path: String,
        val password: String,
        val type: String = "PKCS12",
    ) : TlsCertificateSource

    /**
     * macOS Keychain identity (NWConnection only).
     *
     * Refers to a `SecIdentity` in the system keychain by its label.
     * The private key never leaves the Secure Enclave on supported hardware.
     */
    class SystemKeychain(
        val identityLabel: String,
    ) : TlsCertificateSource
}
