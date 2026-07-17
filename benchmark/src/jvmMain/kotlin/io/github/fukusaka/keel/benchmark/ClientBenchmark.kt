package io.github.fukusaka.keel.benchmark

import io.ktor.client.HttpClient as KtorHttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
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
 * the same mechanism JMH's `gc.alloc.rate.norm` uses), against an in-process
 * keel server fixture on loopback, with Java `HttpClient` and Ktor `CIO`
 * reference drivers. The keel client driver is a stub until the standalone
 * `keel-client-http` (Phase 12b) lands. Open-loop constant-rate mode (for
 * coordinated-omission-free latency) and the JMH allocation harness are
 * scaffolded / follow in later increments.
 */
fun runClientBenchmark(config: BenchmarkConfig) {
    val cc = config.client
    val (targetUrl, stopFixture) = resolveTarget(config)
    try {
        val driver = createDriver(cc.clientType, cc.connections)
        try {
            System.err.println(
                "client bench: type=${driver.name} target=$targetUrl conns=${cc.connections} " +
                    "mode=${cc.mode} warmup=${cc.warmupSec}s duration=${cc.durationSec}s",
            )
            // Warm-up: JIT + connection-pool warm; results discarded.
            if (cc.warmupSec > 0) {
                drive(driver, targetUrl, cc, warmup = true)
            }
            val result = drive(driver, targetUrl, cc, warmup = false)
            printReport(cc, driver.name, result)
        } finally {
            driver.close()
        }
    } finally {
        stopFixture()
    }
}

// --- Fixture resolution ---

/**
 * Returns the target URL to drive plus a stop lambda. When
 * [ClientConfig.targetUrl] is set, uses that external fixture (no-op stop);
 * otherwise starts an in-process keel server fixture ([ClientConfig.fixtureEngine])
 * on [BenchmarkConfig.port] and waits until it answers.
 */
private fun resolveTarget(config: BenchmarkConfig): Pair<String, () -> Unit> {
    val cc = config.client
    cc.targetUrl?.let { return it.trimEnd('/') + cc.endpoint to {} }

    val engines = engineRegistry()
    val fixture = engines[cc.fixtureEngine]
        ?: error("Unknown fixture engine '${cc.fixtureEngine}'. Available: ${engines.keys.joinToString(", ")}")
    val fixtureConfig = config.copy(engine = cc.fixtureEngine, role = "server")
    val stop = fixture.start(fixtureConfig)
    val base = "http://127.0.0.1:${config.port}"
    waitForFixture(base + cc.endpoint)
    return base + cc.endpoint to stop
}

/** Polls the fixture URL until it responds (or fails after a bounded wait). */
private fun waitForFixture(url: String) {
    val probe = HttpClient.newHttpClient()
    val deadline = System.nanoTime() + 30_000_000_000L // 30s
    var lastError: Exception? = null
    while (System.nanoTime() < deadline) {
        try {
            val req = HttpRequest.newBuilder(URI.create(url)).GET().build()
            val resp = probe.send(req, HttpResponse.BodyHandlers.discarding())
            if (resp.statusCode() in 200..299) return
        } catch (e: Exception) {
            lastError = e
        }
        Thread.sleep(100)
    }
    error("Fixture at $url did not become ready within 30s (last error: $lastError)")
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
    fun get(url: String): Int
    fun close() {}
}

private fun createDriver(type: String, connections: Int): ClientDriver = when (type) {
    "java" -> JavaHttpClientDriver(connections)
    "ktor-cio" -> KtorCioDriver(connections)
    "keel" -> error(
        "keel client driver is not implemented yet — pending the standalone keel-client-http " +
            "(Phase 12b). Use --client-type=java or --client-type=ktor-cio for the reference ceiling.",
    )
    else -> error("Unknown client type '$type' (expected: keel | java | ktor-cio)")
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

/**
 * Ktor `CIO` reference driver.
 *
 * **Known limitation (next-increment refinement):** the harness owns a
 * thread-per-connection *blocking* loop and this driver bridges each request
 * with `runBlocking`. That model is a poor fit for a coroutine-native engine —
 * N blocking threads contending the CIO dispatcher throttle throughput and can
 * surface spurious request errors, so the numbers are NOT yet apples-to-apples
 * with the Java driver. Common client-benchmark practice is explicit that the
 * driver's concurrency model must be held fixed across engines (else a
 * coroutine-native client is penalised by a thread-blocking harness); the fair
 * fix is a unified coroutine concurrency model (launch N
 * coroutines on the engine's dispatcher rather than N OS threads), planned for
 * the next increment. Kept here to exercise the harness's multi-driver wiring.
 */
private class KtorCioDriver(connections: Int) : ClientDriver {
    override val name = "ktor-cio"
    private val client = KtorHttpClient(CIO) {
        engine { maxConnectionsCount = connections }
    }

    override fun get(url: String): Int = runBlocking { client.get(url).bodyAsBytes().size }

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
    if (cc.mode == "open" && !warmup) {
        System.err.println(
            "WARN: --client-mode=open (constant-rate, CO-corrected latency) is not implemented yet; " +
                "running closed-loop. Latency percentiles below are closed-loop and may under-report the tail.",
        )
    }
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
