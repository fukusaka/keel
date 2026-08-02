package io.github.fukusaka.keel.server.http

import com.sun.management.ThreadMXBean
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Measures whether the /hello GET allocation rate produces actual GC
 * pressure at single-thread saturation.
 *
 * Drives the same in-process pipeline as
 * [HttpServerHotPathAllocationBenchmark]'s scenario A in a tight loop
 * for a fixed wall-clock duration, then reports:
 *
 * - Throughput (requests / second).
 * - Allocation rate (MB / second), measured via
 *   `ThreadMXBean.getThreadAllocatedBytes` on the busy thread.
 * - Per-GC-collector count delta and accumulated pause time.
 * - Derived "GC time fraction" — % of wall-clock spent inside the
 *   collector.
 *
 * The pipeline runs on `Dispatchers.Unconfined` so the request is
 * processed synchronously on the calling thread. This is a stress
 * harness for the bookkeeping, not a server bench — there is no
 * networking and no concurrent load. The numbers tell us how much GC
 * pressure a single-thread saturating workload generates, which is a
 * fair model for one EventLoop thread at saturation.
 *
 * Output is informational. Does not assert.
 */
// @Ignore: one-time measurement (no functional assertion) — a decision
// aid that caught no regression, so it is not run in the gate / CI; kept
// for re-verification. The verified content + conclusion is the class
// KDoc above.
// Re-run: remove @Ignore, then
//   ./gradlew :keel-server-http:jvmTest --tests "*HelloGcPressureAudit"
@Ignore
class HelloGcPressureAudit {

    @Test
    fun `gc pressure under hello path saturation`() {
        val transport = TestIoTransport()
        try {
            runAudit(transport)
        } finally {
            // Ensure the in-memory pipeline state + pooled IoBufs are
            // released even if any of the measurement / reporting steps
            // throw.
            runCatching { transport.close() }
        }
    }

    private fun runAudit(transport: TestIoTransport) {
        val channel = object : AbstractPipelinedChannel(transport, PrintLogger("audit")) {}
        val scope = CoroutineScope(Dispatchers.Unconfined)
        channel.installHttpServerPipeline(
            Router().apply {
                register(HttpMethod.GET, "/hello") { call ->
                    call.respond(HttpResponse.ok("Hello, World!"))
                }
            },
            emptyList(),
            ErrorHandlers.DEFAULT,
            QueryParameterConfig.DEFAULT,
            scope,
        )

        val request = "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray()
        fun bufOf(): IoBuf {
            val buf = DefaultAllocator.allocate(request.size)
            buf.writeByteArray(request, 0, request.size)
            return buf
        }
        fun drain() {
            val w = transport.written
            for (i in w.indices) w[i].release()
            w.clear()
        }

        // Warm up — JIT, Router trie, codec state machine, allocator pool.
        repeat(WARMUP) {
            channel.pipeline.notifyRead(bufOf())
            drain()
        }

        val tmx = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val tid = Thread.currentThread().threadId()
        val gcs: List<GarbageCollectorMXBean> = ManagementFactory.getGarbageCollectorMXBeans()

        val gcCountBefore = gcs.associate { it.name to it.collectionCount }
        val gcTimeBefore = gcs.associate { it.name to it.collectionTime }
        val allocBefore = tmx.getThreadAllocatedBytes(tid)
        val tStart = System.nanoTime()
        val deadline = tStart + DURATION_NS

        var iterations = 0L
        while (System.nanoTime() < deadline) {
            channel.pipeline.notifyRead(bufOf())
            drain()
            iterations++
        }

        val tEnd = System.nanoTime()
        val allocAfter = tmx.getThreadAllocatedBytes(tid)

        val durationNs = tEnd - tStart
        val durationMs = durationNs / 1_000_000.0
        val totalAlloc = allocAfter - allocBefore
        val perReq = totalAlloc / iterations
        val rps = iterations * 1e9 / durationNs
        val mbPerSec = totalAlloc * 1e9 / durationNs / 1_048_576.0

        println("=== /hello GET — single-thread GC pressure ===")
        println("  duration:       ${"%.3f".format(durationMs / 1000.0)} s")
        println("  iterations:     $iterations")
        println("  throughput:     ${"%,.0f".format(rps)} req / s")
        println("  alloc / req:    $perReq bytes")
        println("  alloc rate:     ${"%.1f".format(mbPerSec)} MB / s")
        println()
        println("  per-collector deltas during the window:")
        var totalGcTime = 0L
        var totalGcCount = 0L
        for (gc in gcs) {
            val dCount = gc.collectionCount - (gcCountBefore[gc.name] ?: 0L)
            val dTime = gc.collectionTime - (gcTimeBefore[gc.name] ?: 0L)
            totalGcCount += dCount
            totalGcTime += dTime
            println(
                "    ${gc.name.padEnd(
                    28,
                )}  count=${dCount.toString().padStart(6)}  time=${dTime.toString().padStart(6)} ms",
            )
        }
        val gcFraction = totalGcTime / durationMs
        println()
        println("  total GC count:       $totalGcCount")
        println("  total GC time:        $totalGcTime ms")
        println("  GC fraction of wall:  ${"%.3f".format(gcFraction * 100)} %")
        println("  ms / 1k req in GC:    ${"%.3f".format(totalGcTime * 1000.0 / iterations)} ms")
        println()
        println("  heap: ${ManagementFactory.getMemoryMXBean().heapMemoryUsage}")
    }

    companion object {
        private const val WARMUP = 5_000

        // 10 seconds is enough to trigger multiple young-gen collections
        // on default G1GC sizing without becoming sensitive to JIT
        // re-compilation tails.
        private const val DURATION_NS = 10_000_000_000L
    }
}
