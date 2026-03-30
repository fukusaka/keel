package io.github.fukusaka.keel.benchmark

/**
 * Per-engine benchmark behavior.
 *
 * Each engine file implements this interface to colocate:
 * - Server startup logic
 * - Tuned socket/engine config for the `tuned` profile
 * - Engine-specific CLI arg merge
 * - OS socket default display values
 *
 * Engines register themselves in the platform-specific [engineRegistry].
 */
interface EngineBenchmark {

    /**
     * Start the server with the given config.
     *
     * Returns a stop callback for lifecycle management. The caller invokes
     * this callback when the process receives a shutdown signal (SIGTERM/SIGINT).
     */
    fun start(config: BenchmarkConfig): () -> Unit

    /** Apply tuned socket overrides. CLI args already in [s] take precedence via `?:`. */
    fun tunedSocket(s: SocketConfig, cpuCores: Int): SocketConfig = s

    /** Apply tuned engine-specific config (e.g., Ktor Netty runningLimit). */
    fun tunedConfig(config: BenchmarkConfig, cpuCores: Int): BenchmarkConfig = config

    /** Merge CLI engine-specific args on top of [base]. */
    fun mergeConfig(base: EngineConfig, args: Map<String, String>): EngineConfig = base

    /** Socket default display values for `--show-config`. */
    fun socketDefaults(os: OsSocketDefaults): SocketConfig.SocketDefaults = SocketConfig.SocketDefaults()
}

/**
 * Platform-specific engine registry.
 *
 * JVM: keel-nio, keel-netty, ktor-cio, ktor-netty, netty-raw, spring, vertx.
 * macOS Native: keel-kqueue, keel-nwconnection, ktor-cio.
 * Linux Native: keel-epoll, ktor-cio.
 */
expect fun engineRegistry(): Map<String, EngineBenchmark>

/** Default engine name for the current platform. */
expect fun defaultEngine(): String

/** Backlog value used by the `tuned` profile for engines that support SO_BACKLOG. */
const val TUNED_BACKLOG = 1024
