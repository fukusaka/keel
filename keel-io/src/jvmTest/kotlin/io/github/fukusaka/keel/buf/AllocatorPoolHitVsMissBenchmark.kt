package io.github.fukusaka.keel.buf

import com.sun.management.ThreadMXBean
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test

/**
 * Diagnostic microbench investigating why `PooledDirectAllocator`
 * exhibited a +21 % throughput regression when L7-a-i prototype used
 * it as the `HttpHeaders` backing (PR #589 retrospective).
 *
 * Three paths exercise the same `IoBuf` lifecycle (allocate + write a
 * tiny payload + release) but vary in how the underlying
 * `DirectByteBuffer` is handled:
 *
 * - **A — pool miss** (`allocate(256)`): no class registered for 256
 *   bytes in the default pool, every iteration falls through to
 *   `ByteBuffer.allocateDirect(256)` and registers a fresh `Cleaner`
 *   that the GC must drain on the next collection.
 * - **B — pool hit** (`allocate(8192)`): the default class is 8 KiB
 *   so the Treiber-stack pop reuses the same `Segment` across
 *   iterations. No `DirectByteBuffer` / `Cleaner` allocation per
 *   iteration after warmup.
 * - **C — heap `ByteArray`** (`ByteArray(256)`): the L7-a-i shipped
 *   choice. No direct memory, no `Cleaner`, just an on-heap byte[].
 *
 * Measures both per-iteration thread alloc (`ThreadMXBean`) and the
 * GC pause delta over a 10 000 iteration window. The wall-clock view
 * shows which path is friendliest to the GC, not just which one
 * allocates fewer bytes.
 *
 * Not a unit test; runs as `@Test` for the normal `jvmTest` task and
 * prints the numbers.
 */
class AllocatorPoolHitVsMissBenchmark {

    private val tmx = ManagementFactory.getThreadMXBean() as ThreadMXBean
    private val gcs: List<GarbageCollectorMXBean> = ManagementFactory.getGarbageCollectorMXBeans()
    private val allocator = PooledDirectAllocator()
    private val payload = "Hello, World!".encodeToByteArray()

    private data class Run(
        val name: String,
        val bytesPerIter: Long,
        val totalGcCount: Long,
        val totalGcTimeMs: Long,
        val wallMs: Double,
    )

    private fun measure(name: String, iterations: Int, body: () -> Unit): Run {
        repeat(WARMUP) { body() }
        // Force a GC to start with a clean slate.
        @Suppress("ExplicitGarbageCollectionCall") System.gc()
        Thread.sleep(50)
        val gcCountBefore = gcs.sumOf { it.collectionCount }
        val gcTimeBefore = gcs.sumOf { it.collectionTime }
        val tid = Thread.currentThread().threadId()
        val allocBefore = tmx.getThreadAllocatedBytes(tid)
        val tStart = System.nanoTime()
        repeat(iterations) { body() }
        val tEnd = System.nanoTime()
        val allocAfter = tmx.getThreadAllocatedBytes(tid)
        val gcCountAfter = gcs.sumOf { it.collectionCount }
        val gcTimeAfter = gcs.sumOf { it.collectionTime }
        return Run(
            name = name,
            bytesPerIter = (allocAfter - allocBefore) / iterations,
            totalGcCount = gcCountAfter - gcCountBefore,
            totalGcTimeMs = gcTimeAfter - gcTimeBefore,
            wallMs = (tEnd - tStart) / 1_000_000.0,
        )
    }

    @Suppress("UNUSED_VARIABLE")
    private var sink = 0

    /** A: pool miss — allocator returns a fresh DirectByteBuffer + Cleaner each time. */
    private fun pathA() {
        val buf = allocator.allocate(256) // 256-byte class not registered → fall through
        buf.writeByteArray(payload, 0, payload.size)
        sink += buf.readableBytes
        buf.release()
    }

    /** B: pool hit — allocator pops the same Segment / DirectByteBuffer across iterations. */
    private fun pathB() {
        val buf = allocator.allocate(8192) // matches the default registered class
        buf.writeByteArray(payload, 0, payload.size)
        sink += buf.readableBytes
        buf.release()
    }

    /** C: plain heap ByteArray — no direct memory, no Cleaner. */
    private fun pathC() {
        val arr = ByteArray(256)
        payload.copyInto(arr, 0, 0, payload.size)
        sink += payload.size
    }

    @Test
    fun `pool miss vs hit vs heap ByteArray — alloc bytes and GC delta`() {
        val a = measure("A (pool miss, allocate 256)", ITERS, ::pathA)
        val b = measure("B (pool hit, allocate 8192)", ITERS, ::pathB)
        val c = measure("C (heap ByteArray 256)", ITERS, ::pathC)

        println("=== Allocator pool miss vs hit vs heap ByteArray (iters=$ITERS) ===")
        listOf(a, b, c).forEach { r ->
            println(
                "  ${r.name.padEnd(34)}  alloc=${r.bytesPerIter} B/iter  " +
                    "GC count=${r.totalGcCount}  GC time=${r.totalGcTimeMs} ms  " +
                    "wall=${"%.0f".format(r.wallMs)} ms",
            )
        }
    }

    companion object {
        private const val WARMUP = 2_000
        // High iteration count so the GC delta is large enough to be
        // visible above measurement noise (Cleaner overhead per direct
        // buffer is small in absolute terms; the regression in the L7
        // audit was visible because the harness ran 2.6M iterations).
        private const val ITERS = 1_000_000
    }
}
