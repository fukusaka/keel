package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import jdk.jfr.consumer.RecordingFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Per-class allocation breakdown for the /hello GET hot path.
 *
 * Drives the same pipeline as
 * [HttpServerHotPathAllocationBenchmark]'s scenario A but with JFR
 * `jdk.ObjectAllocationSample` recording enabled. Aggregates the
 * sampled allocation events by the **top stack frame's class** and
 * prints the top contributors by total bytes.
 *
 * **JFR sampling caveat**: `jdk.ObjectAllocationSample` is a
 * statistical sampling event — events fire on TLAB refills + a tunable
 * rate (default ~150 / s wall clock), not on every allocation. The
 * `weight` field on each event approximates the bytes the JVM
 * attributes to that allocation site. Numbers reported here are
 * **relative**, not absolute — use them to rank sites, then cross-check
 * the absolute totals against
 * [HttpServerHotPathAllocationBenchmark]'s `ThreadMXBean.getThreadAllocatedBytes`
 * baseline (~3320 bytes / request for scenario A).
 *
 * Output format is one line per top class, sorted by total weight:
 * ```
 *   <bytes>  <count>  <class>
 * ```
 *
 * Not a unit test — runs as a `@Test` so it executes under the normal
 * `jvmTest` task; inspect stdout for the breakdown. Does not assert.
 */
// @Ignore: one-time measurement (no functional assertion) — a decision
// aid that caught no regression, so it is not run in the gate / CI; kept
// for re-verification. The verified content + conclusion is the class
// KDoc above.
// Re-run: remove @Ignore, then
//   ./gradlew :keel-server-http:jvmTest --tests "*HelloAllocBreakdownAudit"
@Ignore
class HelloAllocBreakdownAudit {

    @Test
    fun `breakdown of per-class allocations on the hello path`() {
        val transport = TestIoTransport()
        try {
            runAudit(transport)
        } finally {
            // Ensure the in-memory pipeline state + pooled IoBufs are
            // released even if recording / parsing / reporting throws.
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

        val request = ("GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n").encodeToByteArray()
        fun bufOf(bytes: ByteArray): IoBuf {
            val buf = DefaultAllocator.allocate(bytes.size)
            buf.writeByteArray(bytes, 0, bytes.size)
            return buf
        }
        fun drain() {
            val w = transport.written
            for (i in w.indices) w[i].release()
            w.clear()
        }

        // Warm up so JIT compilation / Router trie / codec state machine
        // have settled before JFR starts sampling.
        repeat(WARMUP) {
            channel.pipeline.notifyRead(bufOf(request))
            drain()
        }

        val jfrPath = Files.createTempFile("hello-alloc-", ".jfr")
        val byClass = HashMap<String, LongArray>() // [totalWeight, count]
        var totalEvents = 0L
        var totalWeight = 0L
        try {
            val recording = jdk.jfr.Recording().apply {
                // Sample every allocation site. `jdk.ObjectAllocationSample`
                // gives both TLAB-refill and outside-TLAB events with a
                // weight approximating the allocated bytes.
                enable("jdk.ObjectAllocationSample").withPeriod(Duration.ofMillis(1))
            }
            try {
                recording.start()
                repeat(ITERS) {
                    channel.pipeline.notifyRead(bufOf(request))
                    drain()
                }
                recording.stop()
                recording.dump(jfrPath)
            } finally {
                recording.close()
            }

            RecordingFile(jfrPath).use { rf ->
                while (rf.hasMoreEvents()) {
                    val ev = rf.readEvent()
                    if (ev.eventType.name != "jdk.ObjectAllocationSample") continue
                    totalEvents++
                    val w = ev.getLong("weight")
                    totalWeight += w
                    val objClass = ev.getClass("objectClass") ?: continue
                    val key = objClass.name
                    val acc = byClass.getOrPut(key) { LongArray(2) }
                    acc[0] += w
                    acc[1] += 1
                }
            }
        } finally {
            // Best-effort cleanup of the temp .jfr file even if recording
            // start/stop, dump, or RecordingFile parsing throws.
            Files.deleteIfExists(jfrPath)
        }

        println("=== /hello GET allocation breakdown (JFR jdk.ObjectAllocationSample, iters=$ITERS) ===")
        println("  total events: $totalEvents")
        println("  total sampled weight: $totalWeight bytes")
        println("  approx weight per request: ${if (ITERS > 0) totalWeight / ITERS else 0} bytes")
        println()
        println("  top contributors by weight (top frame's allocated class):")
        println("  ${"bytes".padStart(12)}  ${"count".padStart(8)}  class")
        byClass.entries
            .sortedByDescending { it.value[0] }
            .take(TOP_N)
            .forEach { (cls, acc) ->
                println("  ${acc[0].toString().padStart(12)}  ${acc[1].toString().padStart(8)}  $cls")
            }
    }

    companion object {
        private const val WARMUP = 5_000
        private const val ITERS = 50_000
        private const val TOP_N = 30
    }
}
