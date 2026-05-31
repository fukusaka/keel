package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.server.ServerTlsStrategy
import io.github.fukusaka.keel.server.TlsCodecServerInstaller
import io.github.fukusaka.keel.server.TlsServerConfig
import io.github.fukusaka.keel.server.TlsServerInstaller
import io.github.fukusaka.keel.server.http.dsl.HttpConnectorBuilder
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

/**
 * Validates `--tls` argument early at startup before server construction.
 *
 * Catches TLS backend mismatches (e.g., binary built with OpenSSL but
 * `--tls=mbedtls` requested) and reports a clear error message instead
 * of crashing with an abort trap during server startup.
 *
 * Call this immediately after [BenchmarkConfig.parse] in main().
 */
private var tlsInstallerProvider: ((String) -> TlsServerInstaller)? = null

/** Register a platform-specific TLS installer provider for engine-native TLS. */
fun registerTlsInstallerProvider(provider: (String) -> TlsServerInstaller) {
    tlsInstallerProvider = provider
}

/**
 * Create a [TlsServerConfig] based on the `--tls-installer` option.
 *
 * - `"keel"` (default): wraps the [TlsCodecFactory] in [TlsCodecServerInstaller]
 *   for keel's `TlsHandler`.
 * - `"netty"` etc.: uses an engine-specific installer from the registered provider.
 *
 * @param config Benchmark configuration with `tls` and `tlsInstaller` fields.
 * @return A [TlsServerConfig] and an optional [AutoCloseable] to release (factory lifecycle).
 */
fun createTlsBindConfig(config: BenchmarkConfig): Pair<TlsServerConfig, AutoCloseable?> {
    val backend = requireNotNull(config.tls) { "--tls is required for TLS" }
    val tlsConfig = BenchmarkCertificates.tlsConfig()
    val childOpts = childSocketOptions(config)
    return when (val installerName = config.tlsInstaller) {
        "keel" -> {
            val factory = createTlsCodecFactory(backend)
            TlsServerConfig(tlsConfig, TlsCodecServerInstaller(factory), childSocketOptions = childOpts) to factory
        }
        "nwconnection", "node" -> {
            // Engine-native TLS: installer = null, engine handles TLS at listener level
            TlsServerConfig(tlsConfig, childSocketOptions = childOpts) to null
        }
        else -> {
            val provider = tlsInstallerProvider
                ?: error("No TLS installer provider registered for '$installerName'")
            val installer = provider(installerName)
            TlsServerConfig(tlsConfig, installer, childSocketOptions = childOpts) to null
        }
    }
}

/**
 * Builds a [SocketOptions] for accepted-client fds from the benchmark
 * config's parsed `--send-buffer` / `--receive-buffer` / `--tcp-nodelay`
 * CLI knobs. `tcpNoDelay` defaults to `true` (matching [SocketOptions.DEFAULT])
 * so keel engines behave consistently regardless of whether the flag is
 * explicitly passed. Other fields default to `null` (engine default).
 *
 * Used by both [createTlsBindConfig] (TLS path) and [bindConfigFor]
 * (non-TLS path) so a benchmark scenario like
 * `--send-buffer=4096 --tls=jsse` reaches `setsockopt(SO_SNDBUF)` on
 * every accepted child socket regardless of TLS.
 */
fun childSocketOptions(config: BenchmarkConfig): SocketOptions = SocketOptions(
    // Default true to match SocketOptions.DEFAULT: HTTP workloads consistently
    // benefit from disabling Nagle. Without this, sequential small writes
    // (e.g. one propagateFlush() per WS frame echo) stall ~40 ms per round
    // trip on Linux when a delayed-ACK peer is on the other end.
    tcpNoDelay = config.socket.tcpNoDelay ?: true,
    sendBufferSize = config.socket.sendBuffer,
    receiveBufferSize = config.socket.receiveBuffer,
)

/**
 * Returns the [BindConfig] that the keel POSIX engine benchmarks should
 * pass to `bindPipeline` / `bind`, paired with an [AutoCloseable] for
 * the TLS factory lifecycle (or `null` for non-TLS).
 *
 * Centralises the TLS-vs-plain branching so each engine benchmark
 * stays a one-liner instead of repeating the
 * `if (config.tls != null) createTlsBindConfig(...) else BindConfig(...)`
 * conditional. Both branches now propagate `--send-buffer` /
 * `--receive-buffer` / `--tcp-nodelay` into `BindConfig.childSocketOptions`.
 */
fun bindConfigFor(config: BenchmarkConfig): Pair<BindConfig, AutoCloseable?> =
    if (config.tls != null) {
        createTlsBindConfig(config)
    } else {
        BindConfig(childSocketOptions = childSocketOptions(config)) to null
    }

/**
 * Returns the `connector { … }` configuration lambda the bench's
 * `keel-server-http` (`server-http-*`) engines pass to
 * `keelHttpServer { connector { … } }`, plus an [AutoCloseable] that
 * owns the [TlsCodecFactory] lifecycle (or `null` for plain HTTP).
 *
 * For HTTPS runs the returned lambda calls the connector's `tls { }`
 * sub-block with [ServerTlsStrategy.KeelCodec] (keel's [TlsHandler]
 * driving the supplied factory). Only the `keel` installer is
 * supported here — `netty` / `nwconnection` / `node` installers map
 * to engine-native TLS which the `server-http` connector exposes via
 * [ServerTlsStrategy.EngineNative]; bench-side dispatch for those is
 * deferred (separate scope from the server-http coverage closure).
 */
fun serverHttpConnectorConfig(config: BenchmarkConfig): Pair<HttpConnectorBuilder.() -> Unit, AutoCloseable?> {
    val childOpts = childSocketOptions(config)
    val tlsBackend = config.tls
    if (tlsBackend == null) {
        val configure: HttpConnectorBuilder.() -> Unit = {
            host = "0.0.0.0"
            port = config.port
            socketOptions = childOpts
            applyBenchDosHardening(config.dosHardening)
        }
        return configure to null
    }
    // Only the `keel` installer is supported here; netty / nwconnection / node
    // map to ServerTlsStrategy.EngineNative which is deferred (separate scope
    // from the server-http coverage closure). Fail fast so a manual
    // `bench-one.sh --tls=… --tls-installer=netty` against a server-http
    // engine doesn't silently get KeelCodec strategy and mislead the
    // measurement; the sweep scripts pass `--tls-installer=keel` so they
    // are unaffected.
    require(config.tlsInstaller == "keel") {
        "server-http only supports --tls-installer=keel (got '${config.tlsInstaller}'); " +
            "netty / nwconnection / node installers map to ServerTlsStrategy.EngineNative " +
            "which the server-http connector does expose, but bench-side dispatch is deferred"
    }
    val factory = createTlsCodecFactory(tlsBackend)
    val configure: HttpConnectorBuilder.() -> Unit = {
        host = "0.0.0.0"
        port = config.port
        socketOptions = childOpts
        applyBenchDosHardening(config.dosHardening)
        tls {
            this.config = BenchmarkCertificates.tlsConfig()
            this.strategy = ServerTlsStrategy.KeelCodec(factory)
        }
    }
    return configure to factory
}

/**
 * Applies the strict DoS-hardening connector limits when [enabled], for
 * the `--dos-hardening=true` micro-bench. Turns on query control-char /
 * malformed-encoding rejection and tightens the parameter / header caps
 * so a query-heavy request pays the full validation cost; a no-op when
 * disabled (relaxed defaults, the baseline arm of the sweep).
 */
private fun HttpConnectorBuilder.applyBenchDosHardening(enabled: Boolean) {
    if (!enabled) return
    queryParameters {
        rejectControlCharacters = true
        rejectMalformedEncoding = true
        maxParameterCount = 64
    }
    headerLimits {
        maxHeaderCount = 50
        maxHeaderBytes = 8192
    }
}

fun validateTlsBackend(config: BenchmarkConfig) {
    val backend = config.tls ?: return
    // Non-keel installers (e.g., "node", "netty") handle TLS at the
    // transport level without a TlsCodecFactory, so skip validation.
    if (config.tlsInstaller != "keel") return
    try {
        val factory = createTlsCodecFactory(backend)
        factory.close()
    } catch (e: Exception) {
        printErr("ERROR: --tls=$backend: ${e.message}")
        throw e
    }
}
