package io.github.fukusaka.keel.sample.observability

import io.github.fukusaka.keel.buf.AllocatorStats
import io.github.fukusaka.keel.buf.PooledDirectAllocator
import io.github.fukusaka.keel.buf.SizeTier
import io.github.fukusaka.keel.observability.opentelemetry.OtelAllocatorBinder
import io.github.fukusaka.keel.observability.opentelemetry.OtelStatsCounter
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import kotlin.random.Random

/**
 * Visual-verification sample for the keel BufferAllocator observer hook.
 *
 * Wires a real [PooledDirectAllocator] to OpenTelemetry via
 * [OtelStatsCounter] (push) and [OtelAllocatorBinder] (pull), then runs a
 * sustained allocate / release loop so dashboards have something to chart.
 * OpenTelemetry SDK is auto-configured from the environment — the SigNoz
 * docker-compose at `sample/observability/signoz/` ships an OTLP endpoint
 * on `localhost:4317` by default; point `OTEL_EXPORTER_OTLP_ENDPOINT` at
 * any other receiver to redirect.
 *
 * Sample environment:
 *
 * ```
 * OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
 * OTEL_SERVICE_NAME=keel-observability-sample
 * OTEL_METRIC_EXPORT_INTERVAL=5000
 * OTEL_TRACES_EXPORTER=none
 * OTEL_LOGS_EXPORTER=none
 * OTEL_METRICS_EXPORTER=otlp
 * ```
 *
 * Allocation pattern (per iteration):
 *
 * - 70% page-tier (8 KiB) — exercises the hot path (HIT after warmup,
 *   POOLED on release).
 * - 20% small-tier (≤ 256 B) — exercises a different size class so
 *   `size.tier=tiny` shows traffic too.
 * - 10% huge — exercises the bypass path (HUGE / FREED).
 *
 * Half of every released buffer is held one extra iteration so the pool
 * has a non-zero outstanding count for the pool gauges to chart.
 *
 * **Self-observable output**: the sample also periodically prints the
 * pull-side [AllocatorStats] snapshot to stdout — the same numbers that
 * the OT adapter forwards to `keel.buffer.*` metrics — so the run is
 * visibly working even without a Docker / SigNoz / Grafana backend
 * attached. The OTLP push side stays useful for production-shaped
 * verification through a real backend (Collector logs, SigNoz UI, …).
 *
 * Stop the sample with Ctrl-C; the SDK is closed in the shutdown hook so
 * the OT Collector receives a final flush.
 */
fun main(args: Array<String>) {
    val iters = args.firstOrNull { it.startsWith("--iters=") }
        ?.substringAfter("=")?.toIntOrNull()
        ?: Int.MAX_VALUE

    val sdk = AutoConfiguredOpenTelemetrySdk.builder().build().openTelemetrySdk
    Runtime.getRuntime().addShutdownHook(Thread { sdk.close() })

    val meter = sdk.meterProvider.get(OtelStatsCounter.METER_SCOPE)
    val poolName = "sample"
    val statsCounter = OtelStatsCounter(poolName = poolName, meter = meter)

    val allocator = PooledDirectAllocator(statsCounter = statsCounter)
    OtelAllocatorBinder(allocator = allocator, meter = meter, poolName = poolName)

    println("keel observability sample — emitting to OTLP. Stop with Ctrl-C.")
    println("Pool '$poolName' wired; printing the AllocatorStats snapshot every $SNAPSHOT_INTERVAL_MILLIS ms.")
    sustainedAllocateReleaseLoop(allocator, iters)
}

private fun sustainedAllocateReleaseLoop(
    allocator: PooledDirectAllocator,
    iters: Int,
) {
    val random = Random.Default
    val held = ArrayDeque<io.github.fukusaka.keel.buf.IoBuf>(MAX_HELD)
    var i = 0
    var lastSnapshotAt = System.currentTimeMillis()
    while (i < iters) {
        val roll = random.nextInt(WORKLOAD_BUCKET_TOTAL)
        val size = when {
            roll < WORKLOAD_PAGE_BUCKET -> PAGE_SIZE_BYTES
            roll < WORKLOAD_PAGE_BUCKET + WORKLOAD_TINY_BUCKET -> TINY_SIZE_BYTES
            else -> HUGE_SIZE_BYTES
        }
        val buf = allocator.allocate(size)
        if (held.size < MAX_HELD && random.nextBoolean()) {
            held.addLast(buf)
        } else {
            buf.release()
        }
        if (held.size >= MAX_HELD) {
            held.removeFirst().release()
        }
        if (++i % ITERS_PER_TICK == 0) {
            Thread.sleep(TICK_PAUSE_MILLIS)
            val now = System.currentTimeMillis()
            if (now - lastSnapshotAt >= SNAPSHOT_INTERVAL_MILLIS) {
                printSnapshot(allocator.stats(), iter = i)
                lastSnapshotAt = now
            }
        }
    }
    while (held.isNotEmpty()) held.removeFirst().release()
    printSnapshot(allocator.stats(), iter = i, finalLine = true)
}

private fun printSnapshot(stats: AllocatorStats, iter: Int, finalLine: Boolean = false) {
    val snap = stats.snapshot()
    val tierCached = LongArray(SizeTier.entries.size)
    for (idx in 0 until snap.classCount) {
        tierCached[snap.sizeTier(idx).ordinal] += snap.classCachedCount(idx).toLong()
    }
    val prefix = if (finalLine) "[final]" else "[iter=$iter]"
    println(
        buildString {
            append(prefix).append(' ')
            append("alloc=").append(snap.cumulativeAllocations)
            append(" (hit=").append(snap.cumulativeHits)
            append(" miss=").append(snap.cumulativeMisses)
            append(" empty=").append(snap.cumulativeEmpty)
            append(" huge=").append(snap.cumulativeHuge).append(')')
            append(" rel=").append(snap.cumulativeReleases)
            append(" (pooled=").append(snap.cumulativePooled)
            append(" discarded=").append(snap.cumulativeDiscarded)
            append(" freed=").append(snap.cumulativeFreed).append(')')
            append(" cached[")
            for (ordinal in tierCached.indices) {
                if (ordinal > 0) append(' ')
                append(SizeTier.entries[ordinal].name.lowercase()).append('=').append(tierCached[ordinal])
            }
            append("] chunks=").append(snap.residentChunks)
        },
    )
}

private const val PAGE_SIZE_BYTES = 8192
private const val TINY_SIZE_BYTES = 256
private const val HUGE_SIZE_BYTES = 100_000

// 70 / 20 / 10 split — page tier is the hot path for keel's read buffer
// class, tiny exercises the subpage path, huge exercises the bypass path.
private const val WORKLOAD_BUCKET_TOTAL = 100
private const val WORKLOAD_PAGE_BUCKET = 70
private const val WORKLOAD_TINY_BUCKET = 20

private const val MAX_HELD = 16
private const val ITERS_PER_TICK = 2_000
private const val TICK_PAUSE_MILLIS = 50L
private const val SNAPSHOT_INTERVAL_MILLIS = 3_000L
