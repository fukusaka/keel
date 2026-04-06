package io.github.fukusaka.keel.benchmark

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** Alias for the benchmark server key entry in the KeyStore. */
internal const val BENCHMARK_KEY_ALIAS = "benchmark"

/** Password for the benchmark KeyStore and private key. */
internal val BENCHMARK_KEY_PASSWORD = "changeit".toCharArray()

/** PEM certificate file path for engines that read from files (Vert.x, etc.). */
internal const val BENCHMARK_CERT_PATH = "benchmark/certs/server.crt"

/** PEM private key file path for engines that read from files (Vert.x, etc.). */
internal const val BENCHMARK_KEY_PATH = "benchmark/certs/server.key"

/**
 * Build a JVM [KeyStore] from the benchmark PEM certificate and key.
 *
 * Converts the self-signed PEM certificate from [BenchmarkCertificates]
 * into a PKCS12 KeyStore for use with Ktor's standard `sslConnector`.
 */
internal fun buildBenchmarkKeyStore(): KeyStore {
    val certPem = BenchmarkCertificates.SERVER_CERT
    val keyPem = BenchmarkCertificates.SERVER_KEY

    val certFactory = CertificateFactory.getInstance("X.509")
    val cert = certFactory.generateCertificate(ByteArrayInputStream(certPem.toByteArray()))

    val keyBase64 = keyPem
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replace("\\s".toRegex(), "")
    val keyBytes = Base64.getDecoder().decode(keyBase64)
    val keySpec = PKCS8EncodedKeySpec(keyBytes)
    val privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec)

    val ks = KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    ks.setKeyEntry(BENCHMARK_KEY_ALIAS, privateKey, BENCHMARK_KEY_PASSWORD, arrayOf(cert))
    return ks
}
