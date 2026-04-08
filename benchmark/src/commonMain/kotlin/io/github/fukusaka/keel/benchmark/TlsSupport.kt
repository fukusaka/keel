package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.tls.TlsCodecFactory
import io.github.fukusaka.keel.tls.TlsInstaller

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

/**
 * Validates `--tls` argument early at startup before server construction.
 *
 * Catches TLS backend mismatches (e.g., binary built with OpenSSL but
 * `--tls=mbedtls` requested) and reports a clear error message instead
 * of crashing with an abort trap during server startup.
 *
 * Call this immediately after [BenchmarkConfig.parse] in main().
 */
private var tlsInstallerProvider: ((String) -> TlsInstaller)? = null

/** Register a platform-specific TLS installer provider for engine-native TLS. */
fun registerTlsInstallerProvider(provider: (String) -> TlsInstaller) {
    tlsInstallerProvider = provider
}

/**
 * Create a [TlsInstaller] based on the `--tls-installer` option.
 *
 * - `"keel"` (default): returns the [TlsCodecFactory] itself (uses keel TlsHandler).
 * - `"netty"` etc.: returns an engine-specific installer from the registered provider.
 *
 * @param config Benchmark configuration with `tls` and `tlsInstaller` fields.
 * @return A [TlsInstaller] and an optional [AutoCloseable] to release (factory lifecycle).
 */
fun createTlsInstaller(config: BenchmarkConfig): Pair<TlsInstaller, AutoCloseable?> {
    val backend = requireNotNull(config.tls) { "--tls is required for TLS installer" }
    return when (config.tlsInstaller) {
        "keel" -> {
            val factory = createTlsCodecFactory(backend)
            factory to factory
        }
        else -> {
            val provider = tlsInstallerProvider
                ?: error("No TLS installer provider registered for '${config.tlsInstaller}'")
            provider(config.tlsInstaller) to null
        }
    }
}

fun validateTlsBackend(config: BenchmarkConfig) {
    val backend = config.tls ?: return
    try {
        val factory = createTlsCodecFactory(backend)
        factory.close()
    } catch (e: Exception) {
        printErr("ERROR: --tls=$backend: ${e.message}")
        throw e
    }
}
