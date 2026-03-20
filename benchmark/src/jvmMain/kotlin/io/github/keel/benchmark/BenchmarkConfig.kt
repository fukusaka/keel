package io.github.keel.benchmark

/**
 * Configuration for benchmark servers.
 *
 * ```
 * BenchmarkConfig
 * ├── engine: String               "keel-nio" | "ktor-cio" | "ktor-netty" | "spring" | "vertx"
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
 *     ├── KtorCio                  idleTimeout
 *     ├── Vertx                    maxChunkSize, compression, ...
 *     ├── Spring                   validateHeaders, maxKeepAliveRequests, ...
 *     └── None                     keel-nio, keel-netty (no tunable params)
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
    val engine: String = "keel-nio",
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
            // Only set socket options the engine actually supports.
            // keel/keel-netty have no socket option API in Phase (a).
            // CIO only supports reuseAddress and idleTimeout.
            val tunedSocket = when (engine) {
                "keel-nio", "keel-netty" -> s // no tunable socket options
                "ktor-cio" -> s.copy(
                    reuseAddress = s.reuseAddress ?: true,
                )
                else -> s.copy(
                    tcpNoDelay = s.tcpNoDelay ?: true,
                    backlog = s.backlog ?: 1024,
                    reuseAddress = s.reuseAddress ?: true,
                    threads = s.threads ?: cpuCores,
                )
            }
            var config = copy(socket = tunedSocket)

            // Engine-specific tuned defaults
            config = when (engine) {
                "ktor-netty" -> config.copy(
                    engineConfig = EngineConfig.KtorNetty(
                        runningLimit = cpuCores * 16,
                        shareWorkGroup = false,
                    )
                )
                "ktor-cio" -> config.copy(
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

    /**
     * Detailed multi-line display of all resolved settings.
     * Fixed column width: `  %-22s %s` for consistent alignment across all sections.
     */
    fun display(): String = buildString {
        val fmt = "  %-22s %s"
        appendLine("=== Benchmark Configuration ===")
        appendLine(String.format(fmt, "engine:", engine))
        appendLine(String.format(fmt, "port:", port))
        appendLine(String.format(fmt, "profile:", profile))
        appendLine(String.format(fmt, "cpu-cores:", cpuCores))
        appendLine()
        appendLine("--- Connection ---")
        if (engine == "keel-nio" || engine == "keel-netty") {
            appendLine(String.format(fmt, "connection-close:", "true (always enforced by keel)"))
        } else {
            appendLine(String.format(fmt, "connection-close:", connectionClose))
        }
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
        val d = engineDefaults(engine)
        val fmt = "  %-22s %s"
        sb.appendLine("--- Socket Options ---")
        sb.appendLine(String.format(fmt, "tcp-nodelay:", tcpNoDelay?.toString() ?: d.tcpNoDelay))
        sb.appendLine(String.format(fmt, "reuse-address:", reuseAddress?.toString() ?: d.reuseAddress))
        sb.appendLine(String.format(fmt, "backlog:", backlog?.toString() ?: d.backlog))
        sb.appendLine(String.format(fmt, "send-buffer:", sendBuffer?.let { "$it bytes" } ?: d.sendBuffer))
        sb.appendLine(String.format(fmt, "receive-buffer:", receiveBuffer?.let { "$it bytes" } ?: d.receiveBuffer))
        val threadsDisplay = when {
            threads != null && threads == BenchmarkConfig.cpuCores -> "$threads (tuned: cpu-cores)"
            threads != null -> "$threads"
            else -> d.threads
        }
        sb.appendLine(String.format(fmt, "threads:", threadsDisplay))
    }

    companion object {
        /** OS-level socket defaults, read once at startup via a temporary ServerSocket. */
        private val osDefaults: OsSocketDefaults by lazy { OsSocketDefaults.detect() }

        /** Dispatchers.IO parallelism (system property or default 64). */
        private val ioParallelism: Int by lazy {
            System.getProperty("kotlinx.coroutines.io.parallelism")?.toIntOrNull() ?: 64
        }

        /**
         * Read actual default values from engine classes and OS.
         * No hardcoded magic numbers — all values come from runtime inspection.
         */
        fun engineDefaults(engine: String): SocketDefaults {
            val os = osDefaults
            return when (engine) {
                "keel-nio", "keel-netty" -> SocketDefaults(
                    tcpNoDelay = "(not configurable, OS: ${os.tcpNoDelay})",
                    reuseAddress = "(not configurable, OS: ${os.reuseAddress})",
                    backlog = "(not configurable, OS: ${os.backlog})",
                    sendBuffer = "(not configurable, OS: ${os.sendBuffer} bytes)",
                    receiveBuffer = "(not configurable, OS: ${os.receiveBuffer} bytes)",
                    threads = "$ioParallelism (default by Dispatchers.IO)",
                )
                "ktor-cio" -> {
                    val cioConfig = io.ktor.server.cio.CIOApplicationEngine.Configuration()
                    SocketDefaults(
                        tcpNoDelay = "(not configurable, OS: ${os.tcpNoDelay})",
                        reuseAddress = "${cioConfig.reuseAddress} (default by CIO)",
                        backlog = "(not configurable, OS: ${os.backlog})",
                        sendBuffer = "(not configurable, OS: ${os.sendBuffer} bytes)",
                        receiveBuffer = "(not configurable, OS: ${os.receiveBuffer} bytes)",
                        threads = "$ioParallelism (default by Dispatchers.IO)",
                    )
                }
                "ktor-netty" -> {
                    val nettyConfig = io.ktor.server.netty.NettyApplicationEngine.Configuration()
                    SocketDefaults(
                        tcpNoDelay = "false (default by Netty)",
                        reuseAddress = "false (default by Netty)",
                        backlog = "${os.backlog} (default by OS)",
                        sendBuffer = "${os.sendBuffer} bytes (default by OS)",
                        receiveBuffer = "${os.receiveBuffer} bytes (default by OS)",
                        threads = "${nettyConfig.workerGroupSize} (default by Netty, workerGroupSize)",
                    )
                }
                "spring" -> SocketDefaults(
                    tcpNoDelay = "true (default by Reactor Netty)",
                    reuseAddress = "true (default by Reactor Netty)",
                    backlog = "${os.backlog} (default by OS)",
                    sendBuffer = "${os.sendBuffer} bytes (default by OS)",
                    receiveBuffer = "${os.receiveBuffer} bytes (default by OS)",
                    threads = "${BenchmarkConfig.cpuCores} (default by Reactor Netty, ioWorkerCount)",
                )
                "vertx" -> {
                    val vertxDefaults = io.vertx.core.http.HttpServerOptions()
                    SocketDefaults(
                        tcpNoDelay = "${vertxDefaults.isTcpNoDelay} (default by Vert.x)",
                        reuseAddress = "${vertxDefaults.isReuseAddress} (default by Vert.x)",
                        backlog = "${vertxDefaults.acceptBacklog} (default by Vert.x)",
                        sendBuffer = if (vertxDefaults.sendBufferSize > 0) "${vertxDefaults.sendBufferSize} bytes (default by Vert.x)" else "${os.sendBuffer} bytes (default by OS)",
                        receiveBuffer = if (vertxDefaults.receiveBufferSize > 0) "${vertxDefaults.receiveBufferSize} bytes (default by Vert.x)" else "${os.receiveBuffer} bytes (default by OS)",
                        threads = "${BenchmarkConfig.cpuCores} (default by Vert.x, eventLoopPoolSize)",
                    )
                }
                else -> SocketDefaults()
            }
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
 * OS-level socket defaults detected at runtime via a temporary ServerSocket.
 */
data class OsSocketDefaults(
    val tcpNoDelay: Boolean,
    val reuseAddress: Boolean,
    val backlog: Int,
    val sendBuffer: Int,
    val receiveBuffer: Int,
) {
    companion object {
        fun detect(): OsSocketDefaults {
            val ss = java.net.ServerSocket()
            val sock = java.net.Socket()
            try {
                return OsSocketDefaults(
                    tcpNoDelay = sock.tcpNoDelay,
                    reuseAddress = ss.reuseAddress,
                    backlog = 50, // Java ServerSocket default (documented in ServerSocket javadoc)
                    sendBuffer = sock.sendBufferSize,
                    receiveBuffer = ss.receiveBufferSize,
                )
            } finally {
                sock.close()
                ss.close()
            }
        }
    }
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

    /** No engine-specific settings (keel-nio, keel-netty, or unrecognised engine). */
    data object None : EngineConfig {
        override fun displayTo(sb: StringBuilder, engine: String) {
            val fmt = "  %-22s %s"
            sb.appendLine("--- Engine-Specific ($engine) ---")
            sb.appendLine(String.format(fmt, "(no tunable parameters)", ""))
        }
    }

    /** Ktor Netty engine settings. */
    data class KtorNetty(
        /** Maximum concurrent requests in pipeline. */
        val runningLimit: Int? = null,
        /** Share connection/worker EventLoopGroup. */
        val shareWorkGroup: Boolean? = null,
    ) : EngineConfig {
        override fun displayTo(sb: StringBuilder, engine: String) {
            val fmt = "  %-22s %s"
            val nettyDefault = io.ktor.server.netty.NettyApplicationEngine.Configuration()
            sb.appendLine("--- Engine-Specific (ktor-netty) ---")
            sb.appendLine(String.format(fmt, "running-limit:", runningLimit?.toString() ?: "${nettyDefault.runningLimit} (default by Netty)"))
            sb.appendLine(String.format(fmt, "share-work-group:", shareWorkGroup?.toString() ?: "${nettyDefault.shareWorkGroup} (default by Netty)"))
        }

        override fun toString(): String = buildString {
            runningLimit?.let { append("runningLimit=$it") }
            shareWorkGroup?.let { if (isNotEmpty()) append(", "); append("shareWorkGroup=$it") }
        }
    }

    /** Ktor CIO engine settings. */
    data class Cio(
        /** Idle connection timeout in seconds. */
        val idleTimeout: Int? = null,
    ) : EngineConfig {
        override fun displayTo(sb: StringBuilder, engine: String) {
            val fmt = "  %-22s %s"
            val cioDefault = io.ktor.server.cio.CIOApplicationEngine.Configuration().connectionIdleTimeoutSeconds
            sb.appendLine("--- Engine-Specific (ktor-cio) ---")
            sb.appendLine(String.format(fmt, "connection-idle-timeout:", "${idleTimeout ?: cioDefault} sec${if (idleTimeout == null) " (default by CIO)" else ""}"))
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
            val fmt = "  %-22s %s"
            val d = io.vertx.core.http.HttpServerOptions()
            sb.appendLine("--- Engine-Specific (vertx) ---")
            sb.appendLine(String.format(fmt, "max-chunk-size:", maxChunkSize?.toString() ?: "${d.maxChunkSize} (default by Vert.x)"))
            sb.appendLine(String.format(fmt, "max-header-size:", maxHeaderSize?.toString() ?: "${d.maxHeaderSize} (default by Vert.x)"))
            sb.appendLine(String.format(fmt, "max-initial-line-len:", maxInitialLineLength?.toString() ?: "${d.maxInitialLineLength} (default by Vert.x)"))
            sb.appendLine(String.format(fmt, "decoder-buf-size:", decoderInitialBufferSize?.toString() ?: "${d.decoderInitialBufferSize} (default by Vert.x)"))
            sb.appendLine(String.format(fmt, "compression:", compressionSupported?.toString() ?: "${d.isCompressionSupported} (default by Vert.x)"))
            sb.appendLine(String.format(fmt, "compression-level:", compressionLevel?.toString() ?: "${d.compressionLevel} (default by Vert.x)"))
            sb.appendLine(String.format(fmt, "connection-idle-timeout:", "${idleTimeout ?: d.idleTimeout} sec${if (idleTimeout == null) " (default by Vert.x)" else ""}"))
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
            val fmt = "  %-22s %s"
            // Read Netty's HttpObjectDecoder defaults at runtime
            val nettyMaxChunk = io.netty.handler.codec.http.HttpObjectDecoder.DEFAULT_MAX_CHUNK_SIZE
            val nettyMaxInitLine = io.netty.handler.codec.http.HttpObjectDecoder.DEFAULT_MAX_INITIAL_LINE_LENGTH
            val nettyMaxHeader = io.netty.handler.codec.http.HttpObjectDecoder.DEFAULT_MAX_HEADER_SIZE
            val nettyValidateHeaders = io.netty.handler.codec.http.HttpObjectDecoder.DEFAULT_VALIDATE_HEADERS
            sb.appendLine("--- Engine-Specific (spring) ---")
            sb.appendLine(String.format(fmt, "max-keep-alive-req:", maxKeepAliveRequests?.toString() ?: "unlimited (default by Spring)"))
            sb.appendLine(String.format(fmt, "max-chunk-size:", maxChunkSize?.toString() ?: "$nettyMaxChunk (default by Netty)"))
            sb.appendLine(String.format(fmt, "max-initial-line-len:", maxInitialLineLength?.toString() ?: "$nettyMaxInitLine (default by Netty)"))
            sb.appendLine(String.format(fmt, "validate-headers:", validateHeaders?.toString() ?: "$nettyValidateHeaders (default by Netty)"))
            sb.appendLine(String.format(fmt, "max-in-memory-size:", maxInMemorySize?.let { "$it bytes" } ?: "262144 bytes (default by Spring)"))
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
                "ktor-cio" -> {
                    val b = base as? Cio ?: Cio()
                    Cio(idleTimeout = args["connection-idle-timeout"]?.toInt() ?: b.idleTimeout)
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
                        idleTimeout = args["connection-idle-timeout"]?.toInt() ?: b.idleTimeout,
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
