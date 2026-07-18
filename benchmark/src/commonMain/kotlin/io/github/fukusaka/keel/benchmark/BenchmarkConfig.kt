package io.github.fukusaka.keel.benchmark

/**
 * Configuration for benchmark servers.
 *
 * ```
 * BenchmarkConfig
 * ├── engine: String               engine identifier
 * ├── port: Int                    server listen port
 * ├── profile: String              "default" | "tuned" | "keel-equiv-0.1"
 * ├── connectionClose: Boolean     force Connection: close on all engines
 * ├── tls: String?                 TLS backend: null (HTTP) | "jsse" | "openssl" | "awslc"
 * ├── tlsInstaller: String         TLS installer: "keel" (default) | "netty" | "node"
 * ├── socket: SocketConfig         common socket options (all engines)
 * │   ├── tcpNoDelay               TCP_NODELAY
 * │   ├── reuseAddress             SO_REUSEADDR
 * │   ├── backlog                  SO_BACKLOG
 * │   ├── sendBuffer               SO_SNDBUF
 * │   ├── receiveBuffer            SO_RCVBUF
 * │   └── threads                  worker thread count
 * └── engineConfig: EngineConfig   sealed per-engine settings
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
/** Default server listen port. */
const val DEFAULT_PORT = 8080

data class BenchmarkConfig(
    val engine: String = defaultEngine(),
    val port: Int = DEFAULT_PORT,
    val profile: String = "default",
    val showConfig: Boolean = false,
    val connectionClose: Boolean = false,
    val tls: String? = null,
    val tlsInstaller: String = "keel",
    /**
     * Benchmark role. `server` (default) starts an HTTP server engine for an
     * external load generator (wrk / k6) to hit. `client` runs the client
     * benchmark harness — the process under test is an HTTP *client* driving a
     * fixture server (see [ClientConfig]). Dispatched in the platform `main()`.
     */
    val role: String = "server",
    /** Client-role settings; ignored when [role] is `server`. */
    val client: ClientConfig = ClientConfig(),
    /**
     * When true, enable HTTP response compression (gzip / deflate) on the
     * server. Off by default — preserves existing baseline benchmarks for
     * `/hello`, `/large`, `/upload-stream` etc. that historically ran
     * uncompressed. The k6 client opts in via `Accept-Encoding` (see
     * `benchmark/k6/compression.js`); when this flag is false the server
     * returns the body uncompressed even if the client requests an encoding.
     *
     * Engine support:
     * - Ktor adapters (ktor-cio, ktor-netty, ktor-keel-*): install
     *   `Compression { gzip(); deflate() }` in `benchmarkModule`.
     * - `vertx`: maps to `HttpServerOptions.setCompressionSupported(true)`.
     * - `netty-raw`: adds `HttpContentCompressor` to the pipeline.
     * - `spring`: sets `server.compression.enabled=true` plus mime-type list.
     * - `pipeline-http-*`: not supported — `/keel-codec-http` does not yet
     *   emit a compressed body. The bench surfaces this as a missing
     *   `Content-Encoding` header (k6 check fails) so the gap is visible
     *   on the leaderboard rather than silent.
     */
    val compression: Boolean = false,
    /**
     * Number of pass-through middlewares to install ahead of the route
     * handlers on `server-http-*` engines (0 = none, the default). Each
     * middleware does nothing but call `next()`, so the bench isolates the
     * per-hop dispatch cost of the [io.github.fukusaka.keel.server.http.Middleware]
     * chain: sweep `--middleware-depth` over `/hello` and the throughput
     * delta per added depth is the framework's middleware overhead.
     *
     * `pipeline-http-*` engines have no framework middleware concept, so
     * the flag is a no-op there (the bench compares server-http depths
     * against the pipeline-http floor as depth 0).
     */
    val middlewareDepth: Int = 0,
    /**
     * Number of synthetic GET routes to register under `/bench-route/<i>`
     * ahead of the real routes (0 = none). Lets a sweep grow the route
     * table and measure how match cost scales: hit `/hello` (unrelated
     * path) to see the per-table-size overhead, or `/bench-route/<N/2>`
     * to probe sibling lookup among the N literal children. See
     * [routerGrouped] for the registration-style axis.
     */
    val routerExtraRoutes: Int = 0,
    /**
     * When true, [routerExtraRoutes] routes are registered via nested
     * `route("/bench-route") { get("/$i") }` groups; when false, flat
     * `get("/bench-route/$i")`. Both compile to the same segment trie, so
     * the pair isolates DSL-registration cost (startup) from match cost
     * (per-request, identical) — the bench confirms grouping sugar is
     * zero-cost at the request path.
     */
    val routerGrouped: Boolean = false,
    /**
     * Number of `permessage`-style guarded handlers to register on
     * `/bench-predicate` (0 = none), each gated by a distinct
     * `X-Bench-Sel: v<i>` header predicate plus a final catch-all. A
     * client sending `X-Bench-Sel: v<count-1>` forces evaluating every
     * predicate, so the throughput delta per added count is the
     * per-predicate evaluation cost.
     */
    val predicateCount: Int = 0,
    /**
     * Path-parameter constraint mode for the `/bench-param/:id` route:
     * `"none"` (route disabled), `"plain"` (`:id`), `"int"` (`:id(int)`),
     * `"uuid"` (`:id(uuid)`), or `"regex"` (`:id(^[a-z0-9-]+$)`). Sweeping
     * the mode over a matching value isolates the constraint-check
     * overhead on the extraction hot path.
     */
    val pathParamMode: String = "none",
    /**
     * Size in bytes of the in-memory static asset served at
     * `/bench-static` (0 = route disabled). Lets the bench measure
     * static-file serving throughput and the Range / conditional-GET
     * paths (`Range: bytes=…`, `If-None-Match`) against an asset of known
     * size without touching the filesystem.
     */
    val staticFileBytes: Int = 0,
    /**
     * When true, the `server-http-*` connector is configured with the
     * strict DoS-hardening limits (reject control chars / malformed
     * encoding in query, tighter header / parameter caps) instead of the
     * relaxed defaults. Sweeping it against a query-heavy request
     * isolates the validation overhead on the parse hot path.
     */
    val dosHardening: Boolean = false,
    /**
     * When true (`--profile-alloc`), the engine allocator is wrapped with a
     * shared profiling decorator that records the allocation-size histogram,
     * and the entry point dumps it periodically. Allocation-profiling
     * measurement only; off (and zero-overhead) for normal runs.
     */
    val profileAlloc: Boolean = false,
    /**
     * When true (`--profile-xthread`), the engine allocator is built with a
     * shared [io.github.fukusaka.keel.buf.CrossThreadReleaseProfile] lifecycle
     * listener that records, per size class, the fraction of buffers released on
     * a different thread than they were allocated on. The entry point dumps it
     * periodically. A cross-thread-rate measurement only; off (and
     * zero-overhead) for normal runs.
     */
    val profileXthread: Boolean = false,
    /**
     * Selects the buffer allocator (`--allocator=keel|netty`, default `keel`).
     * `netty` routes buffers through Netty's `PooledByteBufAllocator` — a
     * comparison baseline for keel's own `PooledDirectAllocator`. JVM only, and
     * only for engines that consume `config.allocator` (the NIO engine); the
     * Netty engine always uses `ch.alloc()` and Native ignores it. Profiling
     * flags apply only to the keel allocator.
     */
    val allocatorImpl: String = "keel",
    val socket: SocketConfig = SocketConfig(),
    val engineConfig: EngineConfig = EngineConfig.None,
) {
    companion object {
        fun parse(args: Array<String>): BenchmarkConfig {
            var config = BenchmarkConfig()
            var socket = SocketConfig()
            var client = ClientConfig()
            val engineArgs = mutableMapOf<String, String>()

            for (arg in args) {
                if (arg == "--show-config") { config = config.copy(showConfig = true); continue }
                if (arg == "--profile-alloc") { config = config.copy(profileAlloc = true); continue }
                if (arg == "--profile-xthread") { config = config.copy(profileXthread = true); continue }
                val (key, value) = if ("=" in arg) {
                    arg.substringBefore("=").removePrefix("--") to arg.substringAfter("=")
                } else continue

                when (key) {
                    "engine" -> config = config.copy(engine = value)
                    "port" -> config = config.copy(port = value.toInt())
                    "profile" -> config = config.copy(profile = value)
                    "allocator" -> config = config.copy(allocatorImpl = value)
                    "connection-close" -> config = config.copy(connectionClose = value.toBooleanStrict())
                    "compression" -> config = config.copy(compression = value.toBooleanStrict())
                    "middleware-depth" -> config = config.copy(middlewareDepth = value.toInt())
                    "router-extra-routes" -> config = config.copy(routerExtraRoutes = value.toInt())
                    "router-grouped" -> config = config.copy(routerGrouped = value.toBooleanStrict())
                    "predicate-count" -> config = config.copy(predicateCount = value.toInt())
                    "path-param-mode" -> config = config.copy(pathParamMode = value)
                    "static-file-bytes" -> config = config.copy(staticFileBytes = value.toInt())
                    "dos-hardening" -> config = config.copy(dosHardening = value.toBooleanStrict())
                    "tls" -> config = config.copy(tls = value)
                    "tls-installer" -> config = config.copy(tlsInstaller = value)
                    // Client role
                    "role" -> config = config.copy(role = value)
                    "client-type" -> client = client.copy(clientType = value)
                    "client-endpoint" -> client = client.copy(endpoint = value)
                    "client-connections" -> client = client.copy(connections = value.toInt())
                    "client-duration" -> client = client.copy(durationSec = value.toInt())
                    "client-warmup" -> client = client.copy(warmupSec = value.toInt())
                    "client-requests" -> client = client.copy(requests = value.toInt())
                    "client-mode" -> client = client.copy(mode = value)
                    "client-rate" -> client = client.copy(rateRps = value.toInt())
                    "client-target" -> client = client.copy(targetUrl = value)
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
            config = config.copy(client = client)
            config = config.applyProfile()
            config = config.copy(
                engineConfig = EngineConfig.merge(config.engine, config.engineConfig, engineArgs)
            )
            return config
        }

        private val VALID_PROFILES = setOf("default", "tuned")

        private fun BenchmarkConfig.applyProfile(): BenchmarkConfig = when {
            profile == "default" -> this
            profile == "tuned" -> applyTuned()
            profile.startsWith("keel-equiv") -> {
                val version = profile.removePrefix("keel-equiv").removePrefix("-")
                applyKeelEquiv(version)
            }
            else -> {
                printErr("Unknown profile: $profile")
                printErr("Available: ${VALID_PROFILES.joinToString(", ")}, keel-equiv-<version>")
                benchmarkExit(1)
            }
        }

        /**
         * Auto-calculate optimal values for maximum throughput.
         *
         * Each engine gets only the overrides that differ from its built-in defaults.
         * Platform-specific tuning (JVM engine types) is delegated to [platformApplyTuned].
         * CLI arguments already parsed into [socket] take precedence via `?:`.
         */
        private fun BenchmarkConfig.applyTuned(): BenchmarkConfig {
            val cpuCores = availableProcessors()
            val eb = engineRegistry()[engine]
            val tunedSocket = eb?.tunedSocket(socket, cpuCores) ?: socket
            var config = copy(socket = tunedSocket)
            config = eb?.tunedConfig(config, cpuCores) ?: config
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
        if (tls != null) append(", tls=$tls, tls-installer=$tlsInstaller")
        if (connectionClose) append(", connection=close")
        if (compression) append(", compression=on")
        if (middlewareDepth > 0) append(", middleware-depth=$middlewareDepth")
        if (routerExtraRoutes > 0) append(", router-extra-routes=$routerExtraRoutes${if (routerGrouped) " (grouped)" else ""}")
        if (predicateCount > 0) append(", predicate-count=$predicateCount")
        if (pathParamMode != "none") append(", path-param-mode=$pathParamMode")
        if (staticFileBytes > 0) append(", static-file-bytes=$staticFileBytes")
        if (dosHardening) append(", dos-hardening=on")
        socket.appendTo(this)
        if (engineConfig !is EngineConfig.None) append(", $engineConfig")
    }

    /**
     * Detailed multi-line display of all resolved settings.
     */
    fun display(): String = buildString {
        appendLine("=== Benchmark Configuration ===")
        fmtLine("engine:", engine)
        fmtLine("port:", "$port")
        fmtLine("profile:", profile)
        fmtLine("tls:", tls ?: "disabled")
        if (tls != null) fmtLine("tls-installer:", tlsInstaller)
        fmtLine("cpu-cores:", "${availableProcessors()}")
        appendLine()
        appendLine("--- Connection ---")
        if (isKeelEngine(engine)) {
            fmtLine("connection-close:", "true (always enforced by keel)")
        } else {
            fmtLine("connection-close:", "$connectionClose")
        }
        fmtLine("compression:", if (compression) "enabled (gzip / deflate)" else "disabled")
        fmtLine("middleware-depth:", "$middlewareDepth")
        fmtLine("router-extra-routes:", "$routerExtraRoutes${if (routerGrouped) " (grouped)" else " (flat)"}")
        fmtLine("predicate-count:", "$predicateCount")
        fmtLine("path-param-mode:", pathParamMode)
        fmtLine("static-file-bytes:", "$staticFileBytes")
        fmtLine("dos-hardening:", if (dosHardening) "enabled (strict limits)" else "disabled")
        appendLine()
        socket.displayTo(this, engine)
        appendLine()
        engineConfig.displayTo(this, engine)
    }
}

/** Check if an engine is a keel engine (the synchronous-I/O era enforces Connection: close). */
fun isKeelEngine(engine: String): Boolean =
    engine.startsWith("keel-")
