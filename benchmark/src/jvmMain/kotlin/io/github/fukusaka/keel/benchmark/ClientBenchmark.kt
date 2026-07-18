package io.github.fukusaka.keel.benchmark

import io.ktor.client.HttpClient as KtorHttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.apache5.Apache5
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.java.Java
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
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
 * latency + per-request allocation (`ThreadMXBean.getThreadAllocatedBytes`,
 * the same mechanism JMH's `gc.alloc.rate.norm` uses), driving a SEPARATE
 * fixture process over loopback (`--client-target`; `bench-client.sh` runs
 * rust-bench by default — no shared JVM to contaminate the numbers), with
 * Java `HttpClient` and Ktor `CIO` reference drivers. The keel client driver
 * is a stub until the standalone `keel-client-http` (Phase 12b) lands.
 * Open-loop constant-rate mode (for coordinated-omission-free latency) and the
 * JMH allocation harness are scaffolded / follow in later increments.
 */
fun runClientBenchmark(config: BenchmarkConfig) {
    val cc = config.client
    val targetUrl = requireTarget(cc)
    probeReady(targetUrl)
    val driver = createDriver(cc.clientType, cc.connections)
    try {
        System.err.println(
            "client bench: type=${driver.name} target=$targetUrl conns=${cc.connections} " +
                "mode=${cc.mode} warmup=${cc.warmupSec}s duration=${cc.durationSec}s",
        )
        // Warm-up: JIT + connection-pool warm; results discarded.
        if (cc.warmupSec > 0) {
            driveDispatch(driver, targetUrl, cc, warmup = true)
        }
        val result = driveDispatch(driver, targetUrl, cc, warmup = false)
        printReport(cc, driver.name, result)
    } finally {
        driver.close()
    }
}

// --- Fixture target ---

/**
 * Resolves the fixture URL from the required `--client-target`. The fixture is
 * a SEPARATE process the harness only connects to (`bench-client.sh` manages
 * its lifecycle) — an in-process fixture is deliberately not supported: sharing
 * the client's JVM (heap / GC / CPU / thread scheduler) would contaminate the
 * client's throughput, latency, and especially its per-request allocation (the
 * fixture's allocations would pollute the same `ThreadMXBean` counters). The
 * default fixture is `rust-bench` (axum / hyper / tokio: separate OS process,
 * no JVM, no GC/JIT, high headroom), matching the neutral-server practice of
 * Ktor's and OkHttp's client benchmarks.
 */
private fun requireTarget(cc: ClientConfig): String {
    val url = cc.targetUrl
        ?: error(
            "client bench requires --client-target=<url> pointing at a SEPARATE fixture process " +
                "(e.g. rust-bench on loopback). bench-client.sh starts / stops the fixture. " +
                "In-process fixtures are unsupported: sharing the client JVM contaminates the numbers.",
        )
    return url.trimEnd('/') + cc.endpoint
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
    "ktor-cio" -> KtorCioDriver(connections)
    // Delegating Ktor engines: they inherit the underlying library's keep-alive
    // pool + HTTP/2, so — unlike CIO (KTOR-6503) — they reuse connections.
    "ktor-okhttp" -> KtorEngineDriver("ktor-okhttp", OkHttp)
    "ktor-apache5" -> KtorEngineDriver("ktor-apache5", Apache5)
    "ktor-java" -> KtorEngineDriver("ktor-java", Java)
    "keel" -> error(
        "keel client driver is not implemented yet — pending the standalone keel-client-http " +
            "(Phase 12b). Use --client-type=java|ktor-okhttp|ktor-apache5|ktor-java for the reference ceiling.",
    )
    else -> error(
        "Unknown client type '$type' " +
            "(expected: keel | java | ktor-cio | ktor-okhttp | ktor-apache5 | ktor-java)",
    )
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
private fun driveDispatch(driver: ClientDriver, url: String, cc: ClientConfig, warmup: Boolean): RunResult =
    if (driver.coroutineNative) driveCoroutines(driver, url, cc, warmup) else drive(driver, url, cc, warmup)

/**
 * Coroutine model (for coroutine-native drivers): [ClientConfig.connections]
 * concurrent coroutines on [Dispatchers.Default], each looping a suspend GET.
 * This is the fair comparison for a coroutine client — it is not throttled by
 * OS-thread blocking. Per-request allocation is reported as `n/a` here: under
 * coroutine dispatch the work spreads across pool threads, so the per-thread
 * `ThreadMXBean` sum used by [drive] is unreliable; the dedicated JMH
 * allocation harness (a later increment) measures coroutine-client alloc.
 */
private fun driveCoroutines(driver: ClientDriver, url: String, cc: ClientConfig, warmup: Boolean): RunResult {
    if (cc.mode == "open" && !warmup) warnOpenLoopUnimplemented()
    val seconds = if (warmup) cc.warmupSec else cc.durationSec
    val budget = if (warmup) 0L else cc.requests.toLong()
    val deadline = System.nanoTime() + seconds.toLong() * 1_000_000_000L
    val histogram = Histogram(3)
    val completed = AtomicLong()
    val errors = AtomicLong()
    val issued = AtomicLong()

    val runStart = System.nanoTime()
    runBlocking(Dispatchers.Default) {
        coroutineScope {
            repeat(cc.connections) {
                launch {
                    val local = Histogram(3)
                    while (System.nanoTime() < deadline && (budget == 0L || issued.getAndIncrement() < budget)) {
                        val t0 = System.nanoTime()
                        try {
                            driver.getSuspend(url)
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
    return RunResult(completed.get(), errors.get(), elapsed, histogram, 0L, allocSupported = false)
}

/**
 * Closed-loop: [ClientConfig.connections] worker threads each loop a blocking
 * GET as fast as possible until the deadline (or the total request budget).
 * Records per-request latency into an [Histogram] and sums each worker
 * thread's allocated bytes (`ThreadMXBean`) so the reported bytes/op is the
 * client's per-request allocation.
 *
 * Open-loop (constant-rate, coordinated-omission-free) is not yet wired; when
 * requested it falls back to closed-loop with a warning so latency numbers are
 * not silently mislabelled.
 */
private fun drive(driver: ClientDriver, url: String, cc: ClientConfig, warmup: Boolean): RunResult {
    if (cc.mode == "open" && !warmup) warnOpenLoopUnimplemented()
    val threadBean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
    val allocSupported = threadBean?.isThreadAllocatedMemorySupported == true
    val seconds = if (warmup) cc.warmupSec else cc.durationSec
    val budget = if (warmup) 0L else cc.requests.toLong()
    val deadline = System.nanoTime() + seconds.toLong() * 1_000_000_000L

    val histogram = Histogram(3) // 1..~1h in ns, 3 significant digits
    val completed = AtomicLong()
    val errors = AtomicLong()
    val allocated = AtomicLong()
    val issued = AtomicLong()
    val start = CountDownLatch(1)
    val done = CountDownLatch(cc.connections)

    repeat(cc.connections) {
        thread(name = "client-worker-$it") {
            start.await()
            val tid = Thread.currentThread().threadId()
            val allocStart = if (allocSupported) threadBean.getThreadAllocatedBytes(tid) else 0L
            val local = Histogram(3)
            while (System.nanoTime() < deadline && (budget == 0L || issued.getAndIncrement() < budget)) {
                val t0 = System.nanoTime()
                try {
                    driver.get(url)
                    local.recordValue((System.nanoTime() - t0).coerceAtLeast(1))
                    completed.incrementAndGet()
                } catch (e: Exception) {
                    errors.incrementAndGet()
                }
            }
            if (allocSupported) allocated.addAndGet(threadBean.getThreadAllocatedBytes(tid) - allocStart)
            synchronized(histogram) { histogram.add(local) }
            done.countDown()
        }
    }
    val runStart = System.nanoTime()
    start.countDown()
    done.await()
    val elapsed = System.nanoTime() - runStart

    return RunResult(completed.get(), errors.get(), elapsed, histogram, allocated.get(), allocSupported)
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
