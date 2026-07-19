package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.client.http.KeelHttpClient
import io.github.fukusaka.keel.client.http.dsl.keelHttpClient
import io.github.fukusaka.keel.core.StreamEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.AtomicLong
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.TimeSource

/**
 * Client benchmark harness for Kotlin/Native (`--role=client`), the counterpart
 * of the JVM one.
 *
 * keel exists to do network I/O on native engines, and until this existed the
 * keel HTTP client could only be measured on the JVM — every recorded client
 * figure was `NioEngine`, with kqueue / epoll / io_uring unmeasured. This drives
 * the same client over a native engine, using the same load model, the same
 * percentile method ([LatencyHistogram], verified against `org.HdrHistogram`)
 * and the same output line, so a native run is directly comparable with the JVM
 * run and with the C / Rust / Go / Swift reference clients.
 *
 * **Load model**: closed-loop. [ClientConfig.connections] coroutines each issue
 * a GET as fast as it completes, until the duration (or request budget) runs
 * out. The keel client is coroutine-native, so concurrency is coroutines rather
 * than threads — the same choice the JVM harness makes for it, keeping the
 * concurrency model fixed across the comparison.
 *
 * **bytes/op is not reported.** The JVM harness reads a per-thread allocation
 * counter that has no native equivalent; a keel-allocator counter would measure
 * only pooled `IoBuf` traffic, not the whole picture, and reporting that under
 * the same column heading would invite a false comparison. It prints `n/a`,
 * exactly as the native reference drivers do.
 */
internal fun runNativeClientBenchmark(config: BenchmarkConfig) {
    val cc = config.client
    // "floor-<engine>" measures the transport with no client layers on top; see
    // runRawClientFloor for what it deliberately does not do.
    if (cc.clientType.startsWith(FLOOR_PREFIX)) {
        runNativeRawFloor(cc)
        return
    }
    val targets = clientTargets(cc)
    val engineName = nativeClientEngineName(cc.clientType)

    println("# native client bench: engine=$engineName endpoint=${cc.endpoint} conns=${cc.connections}")
    for (target in targets) probeClientReady(target)

    val warm = runNativeClientRun(engineName, targets, cc, warmup = true)
    printErr("  warmup: ${warm.completed} requests, ${warm.errors} errors")

    val result = runNativeClientRun(engineName, targets, cc, warmup = false)
    printNativeClientReport(cc, cc.clientType, result)
}

/** One measured run: fresh client, N concurrent coroutines, closed loop. */
@Suppress("LongMethod")
private fun runNativeClientRun(
    engineName: String,
    targets: List<String>,
    cc: ClientConfig,
    warmup: Boolean,
): NativeRunResult {
    val seconds = if (warmup) cc.warmupSec else cc.durationSec
    val budget = if (warmup) 0L else cc.requests.toLong()
    // Shared across workers, so atomic: the budget gate must not lose an
    // increment (it would overrun the request count) and the target cursor must
    // not hand two workers the same index.
    val issued = AtomicLong(0)
    val pick = AtomicLong(0)

    val engine = createNativeClientEngine(engineName)
    val client = keelHttpClient(engine) {
        // Size the idle pool to the concurrency so all N connections stay warm,
        // matching the JVM keel driver: a cap below the concurrency leaves the
        // surplus fresh-connecting every round.
        pool { maxIdleConnectionsPerRoute = cc.connections }
    }

    val clock = TimeSource.Monotonic
    val runStart = clock.markNow()
    val deadlineNanos = seconds.toLong() * NANOS_PER_SECOND
    // Dispatchers.Default, matching the JVM harness. A bare runBlocking would
    // confine every worker coroutine to this one thread, so the native side
    // would be measured under a concurrency model the JVM side is not — the
    // asymmetry this harness exists to avoid.
    // Each worker accumulates into its own histogram and counters and hands them
    // back; the merge happens here, after every worker has finished. Merging
    // inside the workers would race — Dispatchers.Default spreads them across
    // threads and LatencyHistogram is deliberately not thread-safe — and the
    // symptom is quiet: lost counter updates read as lower throughput, and a
    // torn counts array reads as a plausible p50 beside a zero p99.
    val callerContext = if (getEnvVar("KEEL_BENCH_CLIENT_CALLER") == "single") {
        EmptyCoroutineContext
    } else {
        Dispatchers.Default
    }
    val perWorker = runBlocking(callerContext) {
        coroutineScope {
            (0 until cc.connections).map { worker ->
                val pinned = if (cc.targetMode == "pinned") targets[worker % targets.size] else null
                async {
                    val local = LatencyHistogram()
                    var localCompleted = 0L
                    var localErrors = 0L
                    while (true) {
                        if (runStart.elapsedNow().inWholeNanoseconds >= deadlineNanos) break
                        // Claim the slot and decide in one operation: reading
                        // then incrementing lets N workers all pass the read
                        // before any of them increments, overrunning the budget.
                        if (budget > 0 && issued.getAndIncrement() >= budget) break
                        val target = pinned ?: targets[(pick.getAndIncrement() % targets.size).toInt()]
                        val started = clock.markNow()
                        try {
                            issueClientGet(client, target)
                            local.record(started.elapsedNow().inWholeNanoseconds.coerceAtLeast(1))
                            localCompleted++
                        } catch (e: CancellationException) {
                            throw e // structured concurrency must still unwind
                        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                            if (localErrors < MAX_REPORTED_ERRORS) printErr("  [err] ${e::class.simpleName}: ${e.message}")
                            localErrors++
                        }
                    }
                    WorkerResult(local, localCompleted, localErrors)
                }
            }.awaitAll()
        }
    }
    val histogram = LatencyHistogram()
    var completed = 0L
    var errors = 0L
    for (w in perWorker) {
        histogram.add(w.latency)
        completed += w.completed
        errors += w.errors
    }
    val elapsed = runStart.elapsedNow().inWholeNanoseconds
    runBlocking {
        client.close()
        engine.close()
    }
    return NativeRunResult(completed, errors, elapsed, histogram)
}

private suspend fun issueClientGet(client: KeelHttpClient, target: String) {
    // Read the body length so the response cannot be optimised away and the
    // full receive path is exercised, as every other driver does.
    val size = client.get(target).body.size
    check(size >= 0) { "negative body size" }
}

/** One worker's tally, merged single-threaded after the run. */
private class WorkerResult(val latency: LatencyHistogram, val completed: Long, val errors: Long)

/** Outcome of one measured run. */
private class NativeRunResult(
    val completed: Long,
    val errors: Long,
    val elapsedNanos: Long,
    val latency: LatencyHistogram,
) {
    val reqPerSec: Double
        get() = if (elapsedNanos <= 0) 0.0 else completed.toDouble() * NANOS_PER_SECOND / elapsedNanos
}

private fun printNativeClientReport(cc: ClientConfig, clientName: String, r: NativeRunResult) {
    fun ms(pct: Double) = r.latency.valueAtPercentile(pct) / NANOS_PER_MILLI
    // Same machine-parseable shape as the JVM harness so bench-client.sh needs
    // no per-platform parsing: <name><endpoint>|rps|p50|p99|p99.9|max|b/op|errors
    println(
        formatClientResultLine(
            name = "$clientName${cc.endpoint}",
            reqPerSec = r.reqPerSec,
            p50 = ms(P50),
            p99 = ms(P99),
            p999 = ms(P999),
            max = ms(P100),
            bytesPerOp = "n/a",
            errors = r.errors,
        ),
    )
    printErr(
        "  req/s=${r.reqPerSec.roundTo(0)}  p50=${ms(P50).roundTo(3)}ms p99=${ms(P99).roundTo(3)}ms " +
            "p99.9=${ms(P999).roundTo(3)}ms max=${ms(P100).roundTo(3)}ms  bytes/op=n/a  errors=${r.errors}  " +
            "(completed=${r.completed})",
    )
}

/** Runs the transport-only floor for `--client-type=floor-<engine>`. */
private fun runNativeRawFloor(cc: ClientConfig) {
    val transport = cc.clientType.removePrefix(FLOOR_PREFIX)
    // Report the string the operator actually typed: mapping floor-nio to
    // "keel-nio" and erroring on that names something they never wrote.
    val engineName = try {
        nativeClientEngineName("keel-$transport")
    } catch (@Suppress("TooGenericExceptionCaught") e: IllegalStateException) {
        error("unsupported --client-type='${cc.clientType}' on this host (${e.message})")
    }
    val (host, port) = floorHostPort(clientTargets(cc).first().removeSuffix(cc.endpoint))
    runBlocking {
        runRawClientFloor(
            engine = createNativeClientEngine(engineName),
            host = host,
            port = port,
            path = cc.endpoint,
            durationSeconds = cc.durationSec,
            warmupSeconds = cc.warmupSec,
            label = cc.clientType,
        )
    }
}

private const val FLOOR_PREFIX = "floor-"
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val NANOS_PER_MILLI = 1_000_000.0
private const val MAX_REPORTED_ERRORS = 3
private const val P50 = 50.0
private const val P99 = 99.0
private const val P999 = 99.9
private const val P100 = 100.0

/**
 * Fails fast when the external fixture is not reachable, before the run starts —
 * a mid-run connection refusal would otherwise be reported as an error count on
 * an otherwise plausible-looking result line.
 */
private fun probeClientReady(target: String) {
    val engine = createNativeClientEngine(nativeClientEngineName("keel"))
    try {
        runBlocking {
            val probe = keelHttpClient(engine)
            try {
                val status = try {
                    probe.get(target).status.code
                } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                    // Without this the connect failure surfaces as a raw
                    // "connect() failed: Connection refused" stack trace, which
                    // says nothing about what the operator should do.
                    error("fixture at $target is not reachable (${e.message}); start it first (bench-client.sh does this)")
                }
                require(status in HTTP_OK_MIN..HTTP_OK_MAX) { "fixture at $target returned HTTP $status (expected 2xx)" }
            } finally {
                probe.close()
            }
        }
    } finally {
        runBlocking { engine.close() }
    }
}

private const val HTTP_OK_MIN = 200
private const val HTTP_OK_MAX = 299

/**
 * The native engine backing a `--client-type`. Accepts the engine-qualified
 * forms (`keel-kqueue`, `keel-epoll`, …) so a sweep can compare engines, and
 * bare `keel` for the host's default.
 */
internal expect fun nativeClientEngineName(clientType: String): String

/** Creates the engine named by [engineName]; the name is already validated. */
internal expect fun createNativeClientEngine(engineName: String): StreamEngine
