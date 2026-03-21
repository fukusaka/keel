package io.github.keel.benchmark

/**
 * Engine-specific configuration, type-safe per engine.
 *
 * ```
 * EngineConfig
 * ├── None                     keel-nio, keel-netty, keel-native (no tunable params)
 * ├── KtorNetty                runningLimit, shareWorkGroup  (JVM only)
 * ├── Cio                      idleTimeout                   (JVM + Native)
 * ├── Vertx                    maxChunkSize, compression...  (JVM only)
 * ├── Spring                   validateHeaders...            (JVM only)
 * └── NettyRaw                 maxContentLength              (JVM only)
 * ```
 *
 * JVM-only variants are defined in jvmMain. commonMain defines [None]
 * and the interface contract.
 */
interface EngineConfig {

    /** Multi-line display of engine-specific settings. */
    fun displayTo(sb: StringBuilder, engine: String)

    /** No engine-specific settings (keel engines, or unrecognised engine). */
    data object None : EngineConfig {
        override fun displayTo(sb: StringBuilder, engine: String) {
            sb.appendLine("--- Engine-Specific ($engine) ---")
            sb.fmtLine("(no tunable parameters)", "")
        }
    }

    companion object {
        /** Merge CLI engine args via the engine registry. */
        fun merge(engine: String, base: EngineConfig, args: Map<String, String>): EngineConfig {
            if (args.isEmpty() && base !is None) return base
            return engineRegistry()[engine]?.mergeConfig(base, args) ?: base
        }
    }
}
