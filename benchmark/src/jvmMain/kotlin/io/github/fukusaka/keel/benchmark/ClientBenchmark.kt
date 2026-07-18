package io.github.fukusaka.keel.benchmark

import io.ktor.client.HttpClient as KtorHttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.apache5.Apache5
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.java.Java
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.http.io.entity.EntityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.HdrHistogram.Histogram
import java.lang.management.ManagementFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Client benchmark harness (`--role=client`) — the inverse of the default
 * server role. The process under test is an HTTP *client* driving a fixture
 * server; the fixture is deliberately trivial (or over-provisioned) so the
 * *client* is the component measured, following common HTTP-client-benchmark
 * practice (Ktor client-benchmarks, undici, OkHttp MockWebServer, h2load, wrk2).
 *
 * First increment (Phase 12b-bench): closed-loop throughput + HdrHistogram
 * latency + per-request allocation (`ThreadMXBean.getTotalThreadAllocatedBytes`,
 * the whole-JVM counter JMH's `gc.alloc.rate.norm` reads on Java 17+), driving a SEPARATE
 * fixture process over loopback (`--client-target`; `bench-client.sh` runs
 * rust-bench by default — no shared JVM to contaminate the numbers), with
 * Java `HttpClient` and Ktor `CIO` reference drivers. The keel client driver
 * is a stub until the standalone `keel-client-http` (Phase 12b) lands.
 * Open-loop constant-rate mode (for coordinated-omission-free latency) and the
 * JMH allocation harness are scaffolded / follow in later increments.
 */
fun runClientBenchmark(config: BenchmarkConfig) {
    val cc = config.client
    val targets = requireTargets(cc)
    targets.forEach { probeReady(it) }
    val driver = createDriver(cc.clientType, cc.connections)
    try {
        System.err.println(
            "client bench: type=${driver.name} targets=${targets.size}[${targets.joinToString()}] " +
                "conns=${cc.connections} mode=${cc.mode} warmup=${cc.warmupSec}s duration=${cc.durationSec}s",
        )
        // Warm-up: JIT + connection-pool warm; results discarded.
        if (cc.warmupSec > 0) {
            driveDispatch(driver, targets, cc, warmup = true)
        }
        val result = driveDispatch(driver, targets, cc, warmup = false)
        printReport(cc, driver.name, result)
    } finally {
        driver.close()
    }
}

// --- Fixture target ---

/**
 * Resolves fixture URLs from the required `--client-target`. The fixture is a
 * SEPARATE process the harness only connects to (`bench-client.sh` manages its
 * lifecycle) — an in-process fixture is deliberately not supported: sharing the
 * client's JVM (heap / GC / CPU / thread scheduler) would contaminate the
 * client's throughput, latency, and especially its per-request allocation (the
 * fixture's allocations would pollute the same `ThreadMXBean` counters). The
 * default fixture is `rust-bench` (axum / hyper / tokio: separate OS process,
 * no JVM, no GC/JIT, high headroom), matching the neutral-server practice of
 * Ktor's and OkHttp's client benchmarks.
 *
 * `--client-target` may be a COMMA-SEPARATED list of fixtures; requests are
 * round-robined across them. Because an HTTP connection pool is keyed by route
 * (host:port), the number of targets changes the per-route concurrency: N
 * targets divide [ClientConfig.connections] concurrency ~N-way per route. This
 * is the axis that exposes CIO's per-route keep-alive defect (KTOR-6503) — one
 * target concentrates all concurrency on a single route (its worst case), while
 * many targets drop each route toward the conns=1 regime where even CIO reuses.
 */
private fun requireTargets(cc: ClientConfig): List<String> {
    val raw = cc.targetUrl
        ?: error(
            "client bench requires --client-target=<url>[,<url>...] pointing at SEPARATE fixture " +
                "process(es) (e.g. rust-bench on loopback). bench-client.sh starts / stops the fixture. " +
                "In-process fixtures are unsupported: sharing the client JVM contaminates the numbers.",
        )
    val targets = raw.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { it.trimEnd('/') + cc.endpoint }
    require(targets.isNotEmpty()) { "no target URL parsed from --client-target='$raw'" }
    return targets
}

/** Fails fast if the external fixture is not reachable before the run starts. */
private fun probeReady(target: String) {
    val probe = HttpClient.newHttpClient()
    val status = try {
        val req = HttpRequest.newBuilder(URI.create(target)).GET().build()
        probe.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        error("fixture at $target is not reachable (${e.message}); start it first (bench-client.sh does this)")
    }
    require(status in 200..299) { "fixture at $target returned HTTP $status (expected 2xx)" }
}

// --- Drivers ---

/**
 * One reference/keel client. [get] performs a single blocking GET and returns
 * the response body byte count; the body MUST be fully read so the JIT cannot
 * dead-code-eliminate the read (a documented benchmark pitfall). Concurrency
 * is owned by the harness (N worker threads), so drivers stay a simple
 * blocking call and every engine is compared under the identical
 * thread-per-connection model.
 */
private interface ClientDriver {
    val name: String

    /**
     * True for a coroutine-native engine (Ktor). The harness then drives it
     * with N concurrent coroutines ([getSuspend]) instead of N blocking OS
     * threads, so a coroutine client is not penalised by a thread-blocking
     * loop — the fair, model-matched comparison.
     */
    val coroutineNative: Boolean get() = false

    fun get(url: String): Int

    /** Suspend GET for the coroutine model; bridges to blocking [get] by default. */
    suspend fun getSuspend(url: String): Int = get(url)

    fun close() {}
}

private fun createDriver(type: String, connections: Int): ClientDriver = when (type) {
    "java" -> JavaHttpClientDriver(connections)
    "okhttp" -> OkHttpDriver(connections)
    "apache5" -> Apache5Driver(connections)
    "ktor-cio" -> KtorCioDriver(connections)
    // Delegating Ktor engines: they inherit the underlying library's keep-alive
    // pool + HTTP/2, so — unlike CIO (KTOR-6503) — they reuse connections.
    "ktor-okhttp" -> KtorEngineDriver("ktor-okhttp", OkHttp)
    "ktor-apache5" -> KtorEngineDriver("ktor-apache5", Apache5)
    "ktor-java" -> KtorEngineDriver("ktor-java", Java)
    "keel" -> error(
        "keel client driver is not implemented yet — pending the standalone keel-client-http " +
            "(Phase 12b). Use a reference client (java|okhttp|apache5|ktor-*) for the ceiling.",
    )
    else -> error(
        "Unknown client type '$type' (expected: keel | java | okhttp | apache5 | " +
            "ktor-cio | ktor-okhttp | ktor-apache5 | ktor-java)",
    )
}

/**
 * Direct OkHttp 5 reference driver (Square) — the most widely used JVM/Android
 * HTTP client. Blocking `execute()` bypasses the async dispatcher's per-host
 * request cap, so the harness's N worker threads run N concurrent calls; the
 * connection pool is sized to [connections] so idle keep-alive sockets are not
 * evicted between requests. Runs on the thread model, so it reports real
 * per-request allocation (bytes/op) — the direct-library counterpart to
 * `ktor-okhttp`, isolating the Ktor client-pipeline overhead from OkHttp itself.
 */
private class OkHttpDriver(connections: Int) : ClientDriver {
    override val name = "okhttp"
    private val client = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(connections, KEEP_ALIVE_MS, TimeUnit.MILLISECONDS))
        .build()

    override fun get(url: String): Int =
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            resp.body.bytes().size
        }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

/**
 * Direct Apache HttpClient 5 (Classic, blocking) reference driver — the
 * enterprise-standard JVM client outside the JDK. The pooling connection
 * manager holds [connections] keep-alive sockets per route. Thread model, so it
 * reports real bytes/op — the direct-library counterpart to `ktor-apache5`.
 */
private class Apache5Driver(connections: Int) : ClientDriver {
    override val name = "apache5"
    private val connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
        .setMaxConnTotal(connections)
        .setMaxConnPerRoute(connections)
        .build()
    private val client = HttpClients.custom().setConnectionManager(connectionManager).build()

    override fun get(url: String): Int =
        client.execute(HttpGet(url)) { resp -> EntityUtils.toByteArray(resp.entity).size }

    override fun close() {
        client.close()
    }
}

/**
 * Ktor driver over a *delegating* engine (OkHttp / Apache5 / Java). These wrap a
 * mature HTTP library, so they inherit its keep-alive connection pool and HTTP/2
 * support — the pooling-capable A/B references, in contrast to the pure-Kotlin
 * CIO engine ([KtorCioDriver]) which does not reuse connections (KTOR-6503).
 * Coroutine-native, so the harness drives it with N concurrent coroutines.
 */
private class KtorEngineDriver(
    override val name: String,
    engineFactory: HttpClientEngineFactory<*>,
) : ClientDriver {
    override val coroutineNative = true
    private val client = KtorHttpClient(engineFactory)

    override suspend fun getSuspend(url: String): Int = client.get(url).bodyAsBytes().size

    override fun get(url: String): Int = runBlocking { getSuspend(url) }

    override fun close() {
        client.close()
    }
}

private class JavaHttpClientDriver(connections: Int) : ClientDriver {
    override val name = "java-httpclient"
    private val executor = Executors.newFixedThreadPool(connections)
    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .executor(executor)
        .build()

    override fun get(url: String): Int {
        val req = HttpRequest.newBuilder(URI.create(url)).GET().build()
        return client.send(req, HttpResponse.BodyHandlers.ofByteArray()).body().size
    }

    override fun close() {
        executor.shutdownNow()
    }
}

/** Idle keep-alive window for the CIO endpoint pool (ms); longer than any run. */
private const val KEEP_ALIVE_MS = 30_000L

/**
 * Ktor `CIO` reference driver — coroutine-native ([coroutineNative] = true), so
 * the harness drives it with N concurrent coroutines rather than N blocking OS
 * threads. This is the model-matched comparison: a coroutine client must not be
 * penalised by a thread-blocking loop (common client-benchmark practice holds
 * the concurrency model fixed across engines).
 *
 * CAVEAT — the CIO client does NOT reuse keep-alive connections under
 * concurrency (KTOR-6503, open against 2.3.6 / 3.3.0; still reproduces on
 * 3.4.1). It opens a fresh TCP connection per request, exhausting ephemeral
 * ports: measured clean only at conns=1 (sequential reuse of one idle socket),
 * churning from conns=2 and collapsing by conns=8. The endpoint pool settings
 * below are the correct config for when the bug is fixed but do NOT currently
 * prevent the churn (neither does enabling pipelining). CIO's numbers here
 * therefore reflect connection churn, not steady-state client cost — the
 * pooling-capable A/B reference is Java `HttpClient` (OkHttp / Apache to come).
 */
private class KtorCioDriver(connections: Int) : ClientDriver {
    override val name = "ktor-cio"
    override val coroutineNative = true
    private val client = KtorHttpClient(CIO) {
        engine {
            maxConnectionsCount = connections
            endpoint.maxConnectionsPerRoute = connections
            endpoint.keepAliveTime = KEEP_ALIVE_MS
        }
    }

    override suspend fun getSuspend(url: String): Int = client.get(url).bodyAsBytes().size

    override fun get(url: String): Int = runBlocking { getSuspend(url) }

    override fun close() {
        client.close()
    }
}

// --- Allocation measurement ---

/**
 * Whole-JVM allocation counter — `com.sun.management.ThreadMXBean.getTotalThreadAllocatedBytes()`,
 * the exact counter JMH's `gc.alloc.rate.norm` reads on Java 17+ (its
 * GlobalHotspotAllocationSnapshot). Snapshotting it across the measured window
 * yields per-request allocation (bytes/op) that captures ALL threads — including
 * a client's internal executor / selector / event-loop threads. A per-worker
 * `getThreadAllocatedBytes` sum (the previous approach) misses those, so it
 * undercounts clients that offload body assembly (java.net.http reads the body
 * on its executor threads) and reports nothing for coroutine-dispatched clients
 * (Ktor). The harness already supplies JMH-equivalent rigor around this counter:
 * a fresh JVM per client type per run (= @Fork), a discarded warm-up phase, a
 * steady-state window, and a RUNS median. Null when the counter is unavailable.
 */
private val allocBean: com.sun.management.ThreadMXBean? =
    (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)
        ?.takeIf { it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled }

/** Whole-JVM allocated bytes so far, or -1 when the counter is unavailable. */
private fun totalAllocatedBytes(): Long = allocBean?.totalThreadAllocatedBytes ?: -1L

/** Allocated bytes since [start], or -1 when unmeasurable. */
private fun allocDelta(start: Long): Long {
    val end = totalAllocatedBytes()
    return if (start >= 0 && end >= 0) end - start else -1L
}

// --- Load loop ---

/** Result of one measured (or warm-up) run. */
private class RunResult(
    val completed: Long,
    val errors: Long,
    val elapsedNanos: Long,
    val latency: Histogram,
    val bytesAllocated: Long,
    val allocSupported: Boolean,
) {
    val reqPerSec: Double get() = completed * 1e9 / elapsedNanos.coerceAtLeast(1)
    val bytesPerOp: Double get() = if (completed == 0L) 0.0 else bytesAllocated.toDouble() / completed
}

private fun warnOpenLoopUnimplemented() {
    System.err.println(
        "WARN: --client-mode=open (constant-rate, CO-corrected latency) is not implemented yet; " +
            "running closed-loop. Latency percentiles below are closed-loop and may under-report the tail.",
    )
}

/** Picks the concurrency model that matches the driver (coroutines vs threads). */
private fun driveDispatch(driver: ClientDriver, targets: List<String>, cc: ClientConfig, warmup: Boolean): RunResult =
    if (driver.coroutineNative) driveCoroutines(driver, targets, cc, warmup) else drive(driver, targets, cc, warmup)

/**
 * Coroutine model (for coroutine-native drivers): [ClientConfig.connections]
 * concurrent coroutines on [Dispatchers.Default], each looping a suspend GET.
 * This is the fair comparison for a coroutine client — it is not throttled by
 * OS-thread blocking. Per-request allocation is measured with the whole-JVM
 * [totalAllocatedBytes] counter (JMH's `gc.alloc.rate.norm` counter), which —
 * unlike a per-thread sum — captures allocation spread across the coroutine
 * dispatcher's pool threads.
 */
private fun driveCoroutines(driver: ClientDriver, targets: List<String>, cc: ClientConfig, warmup: Boolean): RunResult {
    if (cc.mode == "open" && !warmup) warnOpenLoopUnimplemented()
    val seconds = if (warmup) cc.warmupSec else cc.durationSec
    val budget = if (warmup) 0L else cc.requests.toLong()
    val deadline = System.nanoTime() + seconds.toLong() * 1_000_000_000L
    val histogram = Histogram(3)
    val completed = AtomicLong()
    val errors = AtomicLong()
    val issued = AtomicLong()
    val pick = AtomicLong()

    val allocStart = totalAllocatedBytes()
    val runStart = System.nanoTime()
    runBlocking(Dispatchers.Default) {
        coroutineScope {
            repeat(cc.connections) { worker ->
                val pinned = if (cc.targetMode == "pinned") targets[worker % targets.size] else null
                launch {
                    val local = Histogram(3)
                    while (System.nanoTime() < deadline && (budget == 0L || issued.getAndIncrement() < budget)) {
                        val t0 = System.nanoTime()
                        try {
                            driver.getSuspend(pinned ?: targets[(pick.getAndIncrement() % targets.size).toInt()])
                            local.recordValue((System.nanoTime() - t0).coerceAtLeast(1))
                            completed.incrementAndGet()
                        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                            if (errors.getAndIncrement() < 3) {
                                System.err.println("  [err] ${e::class.qualifiedName}: ${e.message}")
                            }
                        }
                    }
                    synchronized(histogram) { histogram.add(local) }
                }
            }
        }
    }
    val elapsed = System.nanoTime() - runStart
    val allocated = allocDelta(allocStart)
    return RunResult(completed.get(), errors.get(), elapsed, histogram, allocated, allocSupported = allocated >= 0)
}

/**
 * Closed-loop: [ClientConfig.connections] worker threads each loop a blocking
 * GET as fast as possible until the deadline (or the total request budget).
 * Records per-request latency into an [Histogram]; per-request allocation is
 * measured with the whole-JVM [totalAllocatedBytes] counter over the run.
 *
 * Open-loop (constant-rate, coordinated-omission-free) is not yet wired; when
 * requested it falls back to closed-loop with a warning so latency numbers are
 * not silently mislabelled.
 */
private fun drive(driver: ClientDriver, targets: List<String>, cc: ClientConfig, warmup: Boolean): RunResult {
    if (cc.mode == "open" && !warmup) warnOpenLoopUnimplemented()
    val seconds = if (warmup) cc.warmupSec else cc.durationSec
    val budget = if (warmup) 0L else cc.requests.toLong()
    val deadline = System.nanoTime() + seconds.toLong() * 1_000_000_000L

    val histogram = Histogram(3) // 1..~1h in ns, 3 significant digits
    val completed = AtomicLong()
    val errors = AtomicLong()
    val issued = AtomicLong()
    val pick = AtomicLong()
    val start = CountDownLatch(1)
    val done = CountDownLatch(cc.connections)

    repeat(cc.connections) { worker ->
        val pinned = if (cc.targetMode == "pinned") targets[worker % targets.size] else null
        thread(name = "client-worker-$worker") {
            start.await()
            val local = Histogram(3)
            while (System.nanoTime() < deadline && (budget == 0L || issued.getAndIncrement() < budget)) {
                val t0 = System.nanoTime()
                try {
                    driver.get(pinned ?: targets[(pick.getAndIncrement() % targets.size).toInt()])
                    local.recordValue((System.nanoTime() - t0).coerceAtLeast(1))
                    completed.incrementAndGet()
                } catch (e: Exception) {
                    errors.incrementAndGet()
                }
            }
            synchronized(histogram) { histogram.add(local) }
            done.countDown()
        }
    }
    val allocStart = totalAllocatedBytes()
    val runStart = System.nanoTime()
    start.countDown()
    done.await()
    val elapsed = System.nanoTime() - runStart
    val allocated = allocDelta(allocStart)

    return RunResult(completed.get(), errors.get(), elapsed, histogram, allocated, allocSupported = allocated >= 0)
}

// --- Reporting ---

private const val NS_PER_MS = 1_000_000.0

private fun printReport(cc: ClientConfig, clientName: String, r: RunResult) {
    fun ms(pct: Double) = r.latency.getValueAtPercentile(pct) / NS_PER_MS
    val alloc = if (r.allocSupported) "%.0f".format(r.bytesPerOp) else "n/a"
    // Machine-parseable line (mirrors the server bench `<name>|<rps>|...` shape),
    // extended with p99.9/max/bytes-per-op/errors for the client axes.
    println(
        "%s|%.0f|%.3f|%.3f|%.3f|%.3f|%s|%d".format(
            "$clientName${cc.endpoint}",
            r.reqPerSec, ms(50.0), ms(99.0), ms(99.9), ms(100.0), alloc, r.errors,
        ),
    )
    System.err.println(
        "  req/s=%.0f  p50=%.3fms p99=%.3fms p99.9=%.3fms max=%.3fms  bytes/op=%s  errors=%d  (completed=%d)"
            .format(r.reqPerSec, ms(50.0), ms(99.0), ms(99.9), ms(100.0), alloc, r.errors, r.completed),
    )
}
