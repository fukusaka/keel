package io.github.keel.benchmark

/**
 * Configuration for benchmark servers.
 *
 * ```
 * BenchmarkConfig
 * ├── engine: String               "keel" | "cio" | "ktor-netty" | "spring" | "vertx"
 * ├── port: Int                    server listen port
 * ├── profile: String              "default" | "tuned" | "keel-equiv-0.1"
 * ├── connectionClose: Boolean     force Connection: close on all engines
 * ├── socket: SocketConfig         common socket options (all engines)
 * │   ├── tcpNoDelay               TCP_NODELAY
 * │   ├── reuseAddress             SO_REUSEADDR
 * │   ├── backlog                  SO_BACKLOG
 * │   ├── sendBuffer               SO_SNDBUF
 * │   ├── receiveBuffer            SO_RCVBUF
 * │   └── threads                  worker thread count
 * └── engineConfig: EngineConfig   sealed per-engine settings
 *     ├── KtorNetty                runningLimit, shareWorkGroup
 *     ├── Cio                      idleTimeout
 *     ├── Vertx                    maxChunkSize, compression, ...
 *     ├── Spring                   validateHeaders, maxKeepAliveRequests, ...
 *     └── None                     keel, keel-netty (no tunable params)
 * ```
 *
 * Three built-in profile families:
 * - **default**: Each engine's out-of-box settings (what users experience first)
 * - **tuned**: Maximum performance, auto-calculated from CPU cores and engine type
 * - **keel-equiv-{version}**: Constrain all engines to match a specific keel version
 *
 * Resolution order: CLI arguments > profile presets > engine defaults.
 * Use `--show-config` to display the fully resolved configuration.
 */
data class BenchmarkConfig(
    val engine: String = "keel",
    val port: Int = 8080,
    val profile: String = "default",
    val showConfig: Boolean = false,
    val connectionClose: Boolean = false,
    val socket: SocketConfig = SocketConfig(),
    val engineConfig: EngineConfig = EngineConfig.None,
) {
    companion object {
        val cpuCores: Int = Runtime.getRuntime().availableProcessors()

        fun parse(args: Array<String>): BenchmarkConfig {
            var config = BenchmarkConfig()
            var socket = SocketConfig()
            val engineArgs = mutableMapOf<String, String>()

            for (arg in args) {
                if (arg == "--show-config") { config = config.copy(showConfig = true); continue }
                val (key, value) = if ("=" in arg) {
                    arg.substringBefore("=").removePrefix("--") to arg.substringAfter("=")
                } else continue

                when (key) {
                    "engine" -> config = config.copy(engine = value)
                    "port" -> config = config.copy(port = value.toInt())
                    "profile" -> config = config.copy(profile = value)
                    "connection-close" -> config = config.copy(connectionClose = value.toBooleanStrict())
                    // Socket options
                    "tcp-nodelay" -> socket = socket.copy(tcpNoDelay = value.toBooleanStrict())
                    "reuse-address" -> socket = socket.copy(reuseAddress = value.toBooleanStrict())
                    "backlog" -> socket = socket.copy(backlog = value.toInt())
                    "send-buffer" -> socket = socket.copy(sendBuffer = value.toInt())
                    "receive-buffer" -> socket = socket.copy(receiveBuffer = value.toInt())
                    "threads" -> socket = socket.copy(threads = value.toInt())
                    // Engine-specific (collected, applied later)
                    else -> engineArgs[key] = value
                }
            }

            config = config.copy(socket = socket)
            config = config.applyProfile()
            // Merge CLI engine args on top of profile-set engine config.
            // If profile set tuned values (e.g., runningLimit=160), CLI args
            // override individual fields while preserving the rest.
            config = config.copy(
                engineConfig = EngineConfig.merge(config.engine, config.engineConfig, engineArgs)
            )
            return config
        }

        /**
         * Apply profile presets after CLI parsing.
         *
         * Resolution: CLI args are parsed first, then profile fills in any
         * remaining nulls. This means `--profile=tuned --threads=2` uses 2
         * threads (CLI wins), not the auto-calculated CPU count.
         */
        private fun BenchmarkConfig.applyProfile(): BenchmarkConfig = when {
            profile == "default" -> this
            profile == "tuned" -> applyTuned()
            profile.startsWith("keel-equiv") -> {
                val version = profile.removePrefix("keel-equiv").removePrefix("-")
                applyKeelEquiv(version)
            }
            else -> this
        }

        /**
         * Auto-calculate optimal values for maximum throughput.
         *
         * Common settings applied to all engines:
         * - tcpNoDelay=true (disable Nagle for low latency)
         * - backlog=1024 (handle burst connections)
         * - reuseAddress=true (fast port recycling)
         * - threads=CPU cores (saturate available parallelism)
         *
         * CLI arguments already parsed into [socket] take precedence via `?:`.
         */
        private fun BenchmarkConfig.applyTuned(): BenchmarkConfig {
            val s = socket
            var config = copy(
                socket = s.copy(
                    tcpNoDelay = s.tcpNoDelay ?: true,
                    backlog = s.backlog ?: 1024,
                    reuseAddress = s.reuseAddress ?: true,
                    threads = s.threads ?: cpuCores,
                ),
            )
            // Engine-specific tuned defaults (applied after EngineConfig.parse,
            // but applyTuned runs before parse — so we set tuned values that
            // parse will later override if CLI args are present)
            config = when (engine) {
                "ktor-netty" -> config.copy(
                    engineConfig = EngineConfig.KtorNetty(
                        runningLimit = cpuCores * 16,
                        shareWorkGroup = false,
                    )
                )
                "cio" -> config.copy(
                    engineConfig = EngineConfig.Cio(idleTimeout = 10)
                )
                "vertx" -> config.copy(
                    engineConfig = EngineConfig.Vertx(
                        decoderInitialBufferSize = 256,
                    )
                )
                "spring" -> config.copy(
                    engineConfig = EngineConfig.Spring(
                        validateHeaders = false,
                    )
                )
                else -> config
            }
            return config
        }

        private fun BenchmarkConfig.applyKeelEquiv(version: String): BenchmarkConfig = when (version) {
            "", "0.1" -> copy(connectionClose = true)
            "0.2" -> this
            else -> copy(connectionClose = true)
        }
    }

    fun summary(): String = buildString {
        append("engine=$engine, port=$port, profile=$profile")
        if (connectionClose) append(", connection=close")
        socket.appendTo(this)
        if (engineConfig !is EngineConfig.None) append(", $engineConfig")
    }

    fun display(): String = buildString {
        appendLine("=== Benchmark Configuration ===")
        appendLine("Engine:     $engine")
        appendLine("Port:       $port")
        appendLine("Profile:    $profile")
        appendLine("CPU cores:  $cpuCores")
        appendLine()
        appendLine("--- Connection ---")
        appendLine("  connection-close: $connectionClose")
        appendLine()
        socket.displayTo(this, engine)
        appendLine()
        engineConfig.displayTo(this, engine)
    }
}

/**
 * Common TCP/IP socket options shared by all engines.
 *
 * All values are nullable: `null` means "use the engine's built-in default".
 * This allows the `tuned` profile to set values via `?:` without overriding
 * explicit CLI arguments that were already parsed.
 *
 * Not all engines support all options. For example, Ktor CIO only honours
 * [reuseAddress]; TCP_NODELAY and backlog are not configurable.
 * See [EngineConfig] for engine-specific parameters.
 */
data class SocketConfig(
    /** Disable Nagle's algorithm for lower latency (TCP_NODELAY). */
    val tcpNoDelay: Boolean? = null,
    /** Allow binding to a port in TIME_WAIT state (SO_REUSEADDR). */
    val reuseAddress: Boolean? = null,
    /** Maximum length of the pending connection queue (SO_BACKLOG). */
    val backlog: Int? = null,
    /** Kernel send buffer size in bytes (SO_SNDBUF). */
    val sendBuffer: Int? = null,
    /** Kernel receive buffer size in bytes (SO_RCVBUF). */
    val receiveBuffer: Int? = null,
    /** Worker thread count. Meaning varies by engine (event loops, I/O workers, etc.). */
    val threads: Int? = null,
) {
    fun appendTo(sb: StringBuilder) {
        tcpNoDelay?.let { sb.append(", tcpNoDelay=$it") }
        reuseAddress?.let { sb.append(", reuseAddress=$it") }
        backlog?.let { sb.append(", backlog=$it") }
        sendBuffer?.let { sb.append(", sendBuffer=$it") }
        receiveBuffer?.let { sb.append(", receiveBuffer=$it") }
        threads?.let { sb.append(", threads=$it") }
    }

    /**
     * Display socket options with engine-specific default values shown in parentheses.
     */
    fun displayTo(sb: StringBuilder, engine: String = "") {
        val defaults = engineDefaults(engine)
        sb.appendLine("--- Socket Options ---")
        sb.appendLine("  tcp-nodelay:    ${tcpNoDelay ?: defaults.tcpNoDelay}")
        sb.appendLine("  reuse-address:  ${reuseAddress ?: defaults.reuseAddress}")
        sb.appendLine("  backlog:        ${backlog ?: defaults.backlog}")
        sb.appendLine("  send-buffer:    ${sendBuffer?.let { "$it bytes" } ?: defaults.sendBuffer}")
        sb.appendLine("  receive-buffer: ${receiveBuffer?.let { "$it bytes" } ?: defaults.receiveBuffer}")
        sb.appendLine("  threads:        ${threads ?: defaults.threads}")
    }

    companion object {
        /**
         * Known default values per engine, displayed when user hasn't overridden.
         */
        fun engineDefaults(engine: String): SocketDefaults = when (engine) {
            "keel" -> SocketDefaults(
                tcpNoDelay = "false (OS default)",
                reuseAddress = "false (OS default)",
                backlog = "128 (OS default)",
                sendBuffer = "(OS default)",
                receiveBuffer = "(OS default)",
                threads = "64 (Dispatchers.IO)",
            )
            "cio" -> SocketDefaults(
                tcpNoDelay = "(not configurable)",
                reuseAddress = "false",
                backlog = "(not configurable)",
                sendBuffer = "(not configurable)",
                receiveBuffer = "(not configurable)",
                threads = "(coroutine-based, no thread pool)",
            )
            "ktor-netty" -> SocketDefaults(
                tcpNoDelay = "false",
                reuseAddress = "false",
                backlog = "128 (OS default)",
                sendBuffer = "(OS default)",
                receiveBuffer = "(OS default)",
                threads = "${BenchmarkConfig.cpuCores / 2 + 1} (workerGroupSize)",
            )
            "spring" -> SocketDefaults(
                tcpNoDelay = "true (Reactor Netty default)",
                reuseAddress = "true (Reactor Netty default)",
                backlog = "(OS default)",
                sendBuffer = "(OS default)",
                receiveBuffer = "(OS default)",
                threads = "${BenchmarkConfig.cpuCores} (ioWorkerCount)",
            )
            "vertx" -> SocketDefaults(
                tcpNoDelay = "true",
                reuseAddress = "false",
                backlog = "1024",
                sendBuffer = "(OS default)",
                receiveBuffer = "(OS default)",
                threads = "${BenchmarkConfig.cpuCores} (eventLoopPoolSize)",
            )
            else -> SocketDefaults()
        }
    }

    data class SocketDefaults(
        val tcpNoDelay: String = "(engine default)",
        val reuseAddress: String = "(engine default)",
        val backlog: String = "(engine default)",
        val sendBuffer: String = "(engine default)",
        val receiveBuffer: String = "(engine default)",
        val threads: String = "(engine default)",
    )
}

/**
 * Engine-specific configuration, type-safe per engine.
 *
 * Each sealed variant declares only the parameters that engine actually supports.
 * This ensures compile-time safety: you cannot accidentally pass a Vert.x-only
 * option to Ktor Netty.
 *
 * CLI arguments not recognised as common socket options are collected into a
 * `Map<String, String>` and dispatched to the appropriate variant by
 * [EngineConfig.parse]. Unknown keys for a given engine are silently ignored.
 *
 * To add a new engine (e.g., for Phase 2 Native benchmarks):
 * 1. Add a new `data class` variant (e.g., `GoGin`, `RustAxum`)
 * 2. Add a case in [parse] to construct it from the args map
 * 3. Apply the config in the engine's start function
 */
sealed interface EngineConfig {

    fun displayTo(sb: StringBuilder, engine: String)

    /** No engine-specific settings (keel, keel-netty, or unrecognised engine). */
    data object None : EngineConfig {
        override fun displayTo(sb: StringBuilder, engine: String) {
            sb.appendLine("--- Engine-Specific ($engine) ---")
            when (engine) {
                "keel" -> {
                    sb.appendLine("  Connection: close (always, Phase (a))")
                    sb.appendLine("  I/O: Dispatchers.IO (max 64 threads)")
                    sb.appendLine("  No tunable parameters in Phase (a)")
                }
                "keel-netty" -> {
                    sb.appendLine("  Delegates to keel NettyEngine")
                    sb.appendLine("  No tunable parameters in Phase (a)")
                }
                else -> sb.appendLine("  (no engine-specific parameters)")
            }
        }
    }

    /** Ktor Netty engine settings. */
    data class KtorNetty(
        /** Maximum concurrent requests in pipeline (default: 32). */
        val runningLimit: Int? = null,
        /** Share connection/worker EventLoopGroup (default: false). */
        val shareWorkGroup: Boolean? = null,
    ) : EngineConfig {
        override fun displayTo(sb: StringBuilder, engine: String) {
            sb.appendLine("--- Engine-Specific (ktor-netty) ---")
            sb.appendLine("  running-limit:    ${runningLimit ?: "32 (default)"}")
            sb.appendLine("  share-work-group: ${shareWorkGroup ?: "false (default)"}")
            sb.appendLine("  configureBootstrap: TCP_NODELAY/SO_BACKLOG from socket config")
            sb.appendLine("  Defaults: workerGroupSize=${BenchmarkConfig.cpuCores / 2 + 1}, callGroupSize=${BenchmarkConfig.cpuCores}")
        }

        override fun toString(): String = buildString {
            runningLimit?.let { append("runningLimit=$it") }
            shareWorkGroup?.let { if (isNotEmpty()) append(", "); append("shareWorkGroup=$it") }
        }
    }

    /** Ktor CIO engine settings. */
    data class Cio(
        /** Idle connection timeout in seconds (default: 45). */
        val idleTimeout: Int? = null,
    ) : EngineConfig {
        override fun displayTo(sb: StringBuilder, engine: String) {
            sb.appendLine("--- Engine-Specific (cio) ---")
            sb.appendLine("  idle-timeout:     ${idleTimeout ?: "45 (default)"} seconds")
            sb.appendLine("  Defaults: reuseAddress=false")
            sb.appendLine("  No TCP_NODELAY/backlog control (CIO limitation)")
        }

        override fun toString(): String = idleTimeout?.let { "idleTimeout=$it" } ?: ""
    }

    /** Vert.x HttpServerOptions beyond common socket config. */
    data class Vertx(
        /** Maximum HTTP chunk size in bytes (default: 8192). */
        val maxChunkSize: Int? = null,
        /** Maximum length of all headers (default: 8192). */
        val maxHeaderSize: Int? = null,
        /** Maximum length of initial HTTP line (default: 4096). */
        val maxInitialLineLength: Int? = null,
        /** HTTP decoder initial buffer size (default: 128). */
        val decoderInitialBufferSize: Int? = null,
        /** Enable gzip/deflate compression (default: false). */
        val compressionSupported: Boolean? = null,
        /** Compression level 1-9 (default: 6). */
        val compressionLevel: Int? = null,
        /** Idle timeout in seconds (default: 0 = no timeout). */
        val idleTimeout: Int? = null,
    ) : EngineConfig {
        override fun displayTo(sb: StringBuilder, engine: String) {
            sb.appendLine("--- Engine-Specific (vertx) ---")
            sb.appendLine("  max-chunk-size:             ${maxChunkSize ?: "8192 (default)"}")
            sb.appendLine("  max-header-size:            ${maxHeaderSize ?: "8192 (default)"}")
            sb.appendLine("  max-initial-line-length:    ${maxInitialLineLength ?: "4096 (default)"}")
            sb.appendLine("  decoder-initial-buffer-size:${decoderInitialBufferSize ?: "128 (default)"}")
            sb.appendLine("  compression-supported:      ${compressionSupported ?: "false (default)"}")
            sb.appendLine("  compression-level:          ${compressionLevel ?: "6 (default)"}")
            sb.appendLine("  idle-timeout:               ${idleTimeout ?: "0 (default)"} seconds")
            sb.appendLine("  Defaults: tcpNoDelay=true, soBacklog=1024, eventLoopPoolSize=${BenchmarkConfig.cpuCores}")
        }

        override fun toString(): String = buildString {
            maxChunkSize?.let { append("maxChunkSize=$it") }
            compressionSupported?.let { if (isNotEmpty()) append(", "); append("compression=$it") }
        }
    }

    /** Spring Boot WebFlux / Reactor Netty settings. */
    data class Spring(
        /** Maximum keep-alive requests per connection (default: unlimited). */
        val maxKeepAliveRequests: Int? = null,
        /** Maximum chunk size (default: 8192). */
        val maxChunkSize: Int? = null,
        /** Maximum initial line length (default: 4096). */
        val maxInitialLineLength: Int? = null,
        /** Enable/disable header validation (default: true). Disabling improves throughput. */
        val validateHeaders: Boolean? = null,
        /** Maximum in-memory buffer size in bytes (default: 262144 = 256KB). */
        val maxInMemorySize: Int? = null,
    ) : EngineConfig {
        override fun displayTo(sb: StringBuilder, engine: String) {
            sb.appendLine("--- Engine-Specific (spring) ---")
            sb.appendLine("  max-keep-alive-requests:  ${maxKeepAliveRequests ?: "(unlimited)"}")
            sb.appendLine("  max-chunk-size:           ${maxChunkSize ?: "8192 (default)"}")
            sb.appendLine("  max-initial-line-length:  ${maxInitialLineLength ?: "4096 (default)"}")
            sb.appendLine("  validate-headers:         ${validateHeaders ?: "true (default)"}")
            sb.appendLine("  max-in-memory-size:       ${maxInMemorySize?.let { "$it bytes" } ?: "262144 (default)"}")
            sb.appendLine("  Defaults: reactor.netty.ioWorkerCount=${BenchmarkConfig.cpuCores}")
        }

        override fun toString(): String = buildString {
            maxKeepAliveRequests?.let { append("maxKeepAliveRequests=$it") }
            validateHeaders?.let { if (isNotEmpty()) append(", "); append("validateHeaders=$it") }
        }
    }

    // Future: Native engines
    // data class GoGin(val gomaxprocs: Int? = null) : EngineConfig { ... }
    // data class RustAxum(val workerThreads: Int? = null) : EngineConfig { ... }

    companion object {
        /**
         * Merge CLI engine args on top of an existing [base] config.
         * The base may come from a profile preset (e.g., tuned).
         * CLI args override individual fields; unset fields keep the base value.
         */
        fun merge(engine: String, base: EngineConfig, args: Map<String, String>): EngineConfig {
            if (args.isEmpty() && base !is None) return base
            return when (engine) {
                "ktor-netty" -> {
                    val b = base as? KtorNetty ?: KtorNetty()
                    KtorNetty(
                        runningLimit = args["running-limit"]?.toInt() ?: b.runningLimit,
                        shareWorkGroup = args["share-work-group"]?.toBooleanStrict() ?: b.shareWorkGroup,
                    )
                }
                "cio" -> {
                    val b = base as? Cio ?: Cio()
                    Cio(idleTimeout = args["idle-timeout"]?.toInt() ?: b.idleTimeout)
                }
                "vertx" -> {
                    val b = base as? Vertx ?: Vertx()
                    Vertx(
                        maxChunkSize = args["max-chunk-size"]?.toInt() ?: b.maxChunkSize,
                        maxHeaderSize = args["max-header-size"]?.toInt() ?: b.maxHeaderSize,
                        maxInitialLineLength = args["max-initial-line-length"]?.toInt() ?: b.maxInitialLineLength,
                        decoderInitialBufferSize = args["decoder-initial-buffer-size"]?.toInt() ?: b.decoderInitialBufferSize,
                        compressionSupported = args["compression-supported"]?.toBooleanStrict() ?: b.compressionSupported,
                        compressionLevel = args["compression-level"]?.toInt() ?: b.compressionLevel,
                        idleTimeout = args["idle-timeout"]?.toInt() ?: b.idleTimeout,
                    )
                }
                "spring" -> {
                    val b = base as? Spring ?: Spring()
                    Spring(
                        maxKeepAliveRequests = args["max-keep-alive-requests"]?.toInt() ?: b.maxKeepAliveRequests,
                        maxChunkSize = args["max-chunk-size"]?.toInt() ?: b.maxChunkSize,
                        maxInitialLineLength = args["max-initial-line-length"]?.toInt() ?: b.maxInitialLineLength,
                        validateHeaders = args["validate-headers"]?.toBooleanStrict() ?: b.validateHeaders,
                        maxInMemorySize = args["max-in-memory-size"]?.toInt() ?: b.maxInMemorySize,
                    )
                }
                else -> base
            }
        }

        /** Parse engine args with no base config (used when no profile sets engine config). */
        fun parse(engine: String, args: Map<String, String>): EngineConfig = merge(engine, None, args)
    }
}
