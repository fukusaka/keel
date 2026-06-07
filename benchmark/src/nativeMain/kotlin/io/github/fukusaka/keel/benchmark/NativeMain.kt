package io.github.fukusaka.keel.benchmark

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix._exit
import platform.posix.signal

/**
 * Kotlin/Native benchmark server entry point.
 *
 * Uses the shared [BenchmarkConfig] from commonMain with platform-specific
 * [engineRegistry] for engine selection.
 *
 * Registers SIGTERM/SIGINT handlers for graceful shutdown. When a signal
 * is received, the server is stopped cleanly so the listen socket is
 * closed and the port is freed immediately.
 */
@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    // Collection alloc micro-bench route: skip server entirely.
    if (args.any { it == "--bench=collection-alloc" }) {
        runCollectionAllocBench()
        return
    }
    if (args.any { it == "--bench=longmap-variants" }) {
        runLongMapVariantBench()
        return
    }
    if (args.any { it == "--bench=poolmap-variants" }) {
        runPoolMapVariantBench()
        return
    }
    if (args.any { it == "--bench=freelist-variants" }) {
        runFreelistVariantBench()
        return
    }
    if (args.any { it == "--bench=freelist-contended" }) {
        runContendedFreelistBench()
        return
    }
    if (args.any { it == "--bench=segment-access" }) {
        runSegmentAccessBench()
        return
    }
    if (args.any { it == "--bench=chain-scan" }) {
        runChainScanBench()
        return
    }
    if (args.any { it == "--bench=iobuf-per-byte" }) {
        runIoBufPerByteDispatchBench()
        return
    }
    if (args.any { it == "--bench=nw-recv-cost" }) {
        if (!runNwRecvCostBench()) {
            printErr("--bench=nw-recv-cost is macOS-only (NW engine is not available on this target)")
            benchmarkExit(1)
        }
        return
    }
    if (args.any { it == "--bench=scopelocal-cost" }) {
        if (!runScopeLocalCostBench()) {
            printErr("--bench=scopelocal-cost is macOS-only (composite + GCD path is Apple)")
            benchmarkExit(1)
        }
        return
    }

    // GC tuning via --gc-target=<bytes> (e.g. --gc-target=256m)
    applyGcTuning(args)

    // Per-connection-queue pool investigation: opt-in bypass of `HttpHeadersPool` recycling.
    // The env var `KEEL_BENCH_HTTP_HEADERS_POOL_BYPASS=1` is read directly
    // by `keel-codec-http` (`HttpHeadersPool` init), avoiding a cross-module
    // call into an `internal` setter from this entry point. The probe is
    // logged here so the bench log makes the active mode visible.
    if (getEnvVar("KEEL_BENCH_HTTP_HEADERS_POOL_BYPASS") == "1") {
        println("KEEL_BENCH_HTTP_HEADERS_POOL_BYPASS=1 — pool recycling disabled")
    }

    val config = BenchmarkConfig.parse(args)
    validateTlsBackend(config)

    val registry = engineRegistry()
    val eb = registry[config.engine]
    if (eb == null) {
        printErr("Unknown engine: ${config.engine}")
        printErr("Available: ${registry.keys.joinToString(", ")}")
        benchmarkExit(1)
    }

    if (config.showConfig) {
        print(config.display())
        return
    }

    // Install signal handlers that call _exit(0) directly. This is
    // async-signal-safe and guarantees immediate process termination.
    // The OS closes all file descriptors (including the listen socket),
    // freeing the port instantly. Using _exit() instead of exit() avoids
    // running atexit handlers and C++ destructors that may deadlock.
    println("Starting benchmark server: ${config.summary()}")
    eb.start(config)

    // Install signal handlers AFTER server start. Ktor/kotlinx-coroutines
    // overrides SIGTERM/SIGINT handlers during engine initialization
    // (verified via sigaction: handler address changes after start()).
    // _exit(0) is async-signal-safe and guarantees immediate termination.
    val handler = staticCFunction { _: Int -> _exit(0) }
    signal(SIGTERM, handler)
    signal(SIGINT, handler)

    // Block main thread. The signal handler terminates the process.
    // Under --profile-alloc, dump the allocation-size histogram every few
    // seconds so the accumulated profile is visible in the bench log before
    // the signal handler _exit(0)s (there is no clean-shutdown dump point).
    if (config.profileAlloc) {
        while (true) {
            platform.posix.sleep(PROFILE_DUMP_INTERVAL_SECONDS)
            println(benchmarkAllocationProfile.format())
        }
    }
    while (true) {
        platform.posix.sleep(60u)
    }
}

// Seconds between `--profile-alloc` histogram dumps.
private const val PROFILE_DUMP_INTERVAL_SECONDS = 3u

@OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
private fun applyGcTuning(args: Array<String>) {
    for (arg in args) {
        if (arg.startsWith("--gc-target=")) {
            val value = arg.removePrefix("--gc-target=")
            val bytes = parseSizeBytes(value)
            kotlin.native.runtime.GC.targetHeapBytes = bytes
            println("GC targetHeapBytes=$bytes")
        }
        if (arg == "--gc-no-autotune") {
            kotlin.native.runtime.GC.autotune = false
            println("GC autotune=false")
        }
    }
}

private fun parseSizeBytes(s: String): Long {
    val lower = s.lowercase()
    return when {
        lower.endsWith("m") -> lower.dropLast(1).toLong() * 1024 * 1024
        lower.endsWith("g") -> lower.dropLast(1).toLong() * 1024 * 1024 * 1024
        lower.endsWith("k") -> lower.dropLast(1).toLong() * 1024
        else -> s.toLong()
    }
}
