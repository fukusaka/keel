package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.tls.TlsCodecFactory

/**
 * Pluggable factory provider for TLS benchmarking.
 *
 * Platform-specific TLS source sets (jvmTls, macosTls, linuxTls) register
 * their provider via [registerTlsProvider]. When built without `-Ptls`,
 * this remains null and `--tls` produces a runtime error.
 */
private var tlsProvider: ((String) -> TlsCodecFactory)? = null

/** Register a platform-specific TLS codec factory provider. */
fun registerTlsProvider(provider: (String) -> TlsCodecFactory) {
    tlsProvider = provider
}

/**
 * Create a [TlsCodecFactory] for the specified TLS backend.
 *
 * @param backend TLS backend name: "jsse" (JVM), "openssl" or "awslc" (Native).
 * @throws IllegalStateException if TLS support is not available (build without `-Ptls`).
 * @throws IllegalArgumentException if the backend is not available on the current platform.
 */
fun createTlsCodecFactory(backend: String): TlsCodecFactory {
    val provider = tlsProvider
        ?: error("TLS benchmarking requires -Ptls build flag. Rebuild with: ./gradlew -Pbenchmark -Ptls ...")
    return provider(backend)
}
