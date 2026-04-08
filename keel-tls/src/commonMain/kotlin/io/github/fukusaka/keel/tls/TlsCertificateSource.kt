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
 * The [TlsCodecFactory] implementation is responsible for converting a
 * [TlsCertificateSource] into the platform-native representation.
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

/**
 * Converts this certificate source to [TlsCertificateSource.Pem].
 *
 * - [Pem][TlsCertificateSource.Pem] → returns as-is.
 * - [Der][TlsCertificateSource.Der] → Base64-encodes to PEM.
 * - [KeyStoreFile][TlsCertificateSource.KeyStoreFile],
 *   [SystemKeychain][TlsCertificateSource.SystemKeychain] → throws.
 *   KeyStoreFile requires platform-specific KeyStore API;
 *   SystemKeychain private keys cannot be extracted.
 */
fun TlsCertificateSource.asPem(): TlsCertificateSource.Pem = when (this) {
    is TlsCertificateSource.Pem -> this
    is TlsCertificateSource.Der -> TlsCertificateSource.Pem(
        certificatePem = PemDerConverter.derToPem(certificate, "CERTIFICATE"),
        privateKeyPem = PemDerConverter.derToPem(privateKey, "PRIVATE KEY"),
    )
    is TlsCertificateSource.KeyStoreFile ->
        error("KeyStoreFile cannot be converted to PEM without platform-specific KeyStore API")
    is TlsCertificateSource.SystemKeychain ->
        error("SystemKeychain private keys cannot be extracted")
}

/**
 * Converts this certificate source to [TlsCertificateSource.Der].
 *
 * - [Der][TlsCertificateSource.Der] → returns as-is.
 * - [Pem][TlsCertificateSource.Pem] → Base64-decodes to DER.
 * - [KeyStoreFile][TlsCertificateSource.KeyStoreFile],
 *   [SystemKeychain][TlsCertificateSource.SystemKeychain] → throws.
 */
fun TlsCertificateSource.asDer(): TlsCertificateSource.Der = when (this) {
    is TlsCertificateSource.Der -> this
    is TlsCertificateSource.Pem -> TlsCertificateSource.Der(
        certificate = PemDerConverter.pemToDer(certificatePem),
        privateKey = PemDerConverter.pemToDer(privateKeyPem),
    )
    is TlsCertificateSource.KeyStoreFile ->
        error("KeyStoreFile cannot be converted to DER without platform-specific KeyStore API")
    is TlsCertificateSource.SystemKeychain ->
        error("SystemKeychain private keys cannot be extracted")
}
