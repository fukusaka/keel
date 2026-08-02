package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import kotlinx.cinterop.ExperimentalForeignApi
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_async
import platform.darwin.dispatch_group_create
import platform.darwin.dispatch_group_enter
import platform.darwin.dispatch_group_leave
import platform.darwin.dispatch_group_wait
import platform.darwin.dispatch_queue_create
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Synthetic cross-queue header-pool repro: exercise [HttpHeadersPool.borrow] / [HttpHeaders.release]
 * cycles on a GCD serial queue (the NWConnection engine pattern) to test
 * whether the `@ThreadLocal nativeStack` design survives GCD's worker-thread
 * migration.
 *
 * **Hypothesis under test.** `HttpHeadersPool` uses
 * `@ThreadLocal private val nativeStack: ArrayDeque<HttpHeaders>` for per-
 * EventLoop-thread recycling. K/N's `@ThreadLocal` is pthread-specific.
 * On pthread-pinned engines (kqueue / epoll / io_uring / NIO / Netty) this
 * is correct because each EventLoop is bound to exactly one pthread, so
 * `borrow` and `giveBack` for any one connection always touch the same
 * `@ThreadLocal` slot. **NWConnection's GCD serial queue does not satisfy
 * this invariant** — blocks on the same queue can run on different GCD
 * worker pthreads. The KDoc of `HttpHeadersPool` acknowledges the
 * "per-EventLoop = per-thread" assumption explicitly; this test probes
 * what happens when that assumption breaks.
 *
 * **What we expect under the hypothesis.** Three observable symptoms,
 * in increasing severity:
 *
 * 1. **Pool fragmentation (mild)** — `borrow` on worker B sees an empty
 *    pool even though worker A's pool has free instances, so the borrow
 *    falls through to a fresh `HttpHeaders()` allocation. Pool hit ratio
 *    drops, GC pressure rises, but no crash.
 *
 * 2. **Cross-thread giveBack (moderate)** — a borrow on worker A followed
 *    by a release on worker B pushes the instance onto B's stack. Worker
 *    A's stack permanently loses that instance; over many requests, A's
 *    pool drains while B's grows past the [HttpHeadersPool.MAX_POOLED]
 *    cap intention. No crash but the per-thread cap (`MAX_POOLED = 64`)
 *    is silently violated in aggregate.
 *
 * 3. **Aliasing / double-borrow (severe — matches the cross-queue header-pool crash signature)** —
 *    if a suspending handler `borrow`s on worker A, suspends, and resumes
 *    on worker B, then a fresh callback on worker A may `borrow` and get
 *    a different instance while the original handler still owns the first.
 *    No aliasing happens here (both pools are disjoint), but `release`
 *    can fire while the instance is still in use by a concurrent block
 *    on a sibling queue — see the multi-queue stress test below.
 *
 * The crash pattern (`HttpHeaders.resetForReuse → null deref`,
 * `slotCount=1 + extras empty + segmentLog2 stale`) is consistent with
 * symptom (3) — the instance was reset mid-mutation by a concurrent
 * sibling, leaving torn internal state. This test attempts to surface
 * that condition synthetically without involving NWConnection itself.
 *
 * **Methodology.** Two complementary stress shapes:
 * - `single-queue serial borrow/release` — verifies the within-one-queue
 *   case is safe. Even with GCD migration, blocks on a single serial
 *   queue execute in strict order so no two borrows alias.
 * - `multi-queue concurrent borrow/release` — submits work to several
 *   serial queues concurrently. Each queue is independent; with GCD
 *   migration, two queues' workers may map to the same OS thread at
 *   different times. If the `@ThreadLocal` stack ever ends up serving
 *   two queues simultaneously through the same worker, ArrayDeque
 *   corruption / double-pop is possible.
 *
 * A pass on both tests means the `@ThreadLocal` design is robust under
 * NWConnection's GCD model and the race's root cause is elsewhere. A failure
 * (any thrown exception, hang, or crash) pinpoints the design defect.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
class PocHeadersPoolGcdRaceTest {

    /**
     * Hot loop on a single serial queue. Each block does the full
     * `borrow → mutate → release` cycle. GCD will migrate the queue
     * across its worker threads under load, so successive iterations
     * touch different `@ThreadLocal` stacks. We expect no crash and no
     * exception — the within-iteration ordering is strict so there is
     * no aliasing.
     */
    @Test
    fun `single serial queue borrow release survives GCD migration`() {
        val queue = dispatch_queue_create("keel.poc.headers-pool.single", null)
        val errors = AtomicInt(0)
        val firstError = AtomicInt(0)
        val iterations = 100_000

        val group = dispatch_group_create()
        repeat(iterations) {
            dispatch_group_enter(group)
            dispatch_async(queue) {
                try {
                    val headers = HttpHeadersPool.borrow()
                    headers.add("X-Test", "value")
                    headers.add("X-Test-2", "value2")
                    headers.release()
                } catch (t: Throwable) {
                    if (errors.fetchAndAdd(1) == 0) {
                        firstError.store(1)
                        println("[single-queue] first error: ${t::class.simpleName}: ${t.message}")
                    }
                }
                dispatch_group_leave(group)
            }
        }

        dispatch_group_wait(group, DISPATCH_TIME_FOREVER)

        assertEquals(
            expected = 0,
            actual = errors.load(),
            message = "Single-queue borrow/release threw ${errors.load()} times / $iterations. " +
                "Indicates @ThreadLocal corruption under GCD worker migration on a single " +
                "serial queue — direct evidence that HttpHeadersPool's per-thread pool " +
                "design is unsafe for NWConnection.",
        )
    }

    /**
     * Multiple serial queues hitting the pool in parallel. This is the
     * direct cross-queue header-pool shape: NWConnection assigns each connection its own
     * `connectionQueue`; under load, many connections' queues map to the
     * same small GCD worker thread pool concurrently. If the
     * `@ThreadLocal nativeStack` ever has two queues' blocks executing
     * on the same worker simultaneously (which Apple's contract does
     * NOT preclude — only same-queue serialisation is guaranteed), the
     * pool's ArrayDeque (non-thread-safe) can be corrupted.
     */
    @Test
    fun `multi serial queue concurrent borrow release does not corrupt pool`() {
        val queueCount = 8
        val queues = Array(queueCount) { idx ->
            dispatch_queue_create("keel.poc.headers-pool.multi-$idx", null)
        }
        val errors = AtomicInt(0)
        val firstErrorPrinted = AtomicInt(0)
        val iterationsPerQueue = 20_000

        val group = dispatch_group_create()
        for (q in queues) {
            repeat(iterationsPerQueue) {
                dispatch_group_enter(group)
                dispatch_async(q) {
                    try {
                        val headers = HttpHeadersPool.borrow()
                        // Force some real work so the borrow → release
                        // window is non-trivial: gives GCD a chance to
                        // suspend / migrate this block.
                        headers.add("X-Conn", "loopback")
                        headers.add("X-Path", "/poc")
                        headers.add("X-Misc", "abcdef")
                        headers.release()
                    } catch (t: Throwable) {
                        if (errors.fetchAndAdd(1) < 5 && firstErrorPrinted.compareAndSet(0, 1)) {
                            println("[multi-queue] error: ${t::class.simpleName}: ${t.message}")
                        }
                    }
                    dispatch_group_leave(group)
                }
            }
        }

        dispatch_group_wait(group, DISPATCH_TIME_FOREVER)

        val observed = errors.load()
        val totalIterations = queueCount * iterationsPerQueue
        if (observed > 0) {
            fail(
                "Multi-queue concurrent borrow/release threw $observed times / " +
                    "$totalIterations. Indicates @ThreadLocal pool corruption under GCD " +
                    "cross-queue worker sharing — confirms the cross-queue header-pool root-cause hypothesis: " +
                    "HttpHeadersPool's per-thread design is unsafe when multiple GCD serial " +
                    "queues compete for the same worker thread pool.",
            )
        }
    }

    /**
     * Hypothesis (e): backing [IoBuf] race via [HttpHeaders.addRange].
     *
     * Each block borrows an [HttpHeaders], calls `addRange(sharedBuf, ...)` —
     * which does `backing = sharedBuf; sharedBuf.retain()` — then `release()`s
     * the headers, which calls `backing.release()` via `resetForReuse`. Net
     * refcount delta per block is zero, but every block briefly toggles
     * [sharedBuf]'s refcount across the +1/−1 boundary.
     *
     * If the crash stack (`HttpHeaders.resetForReuse → backing.release()`
     * UAF) is rooted in a refcount race on a shared backing buffer — e.g.
     * the `backing = buf` store visible to a sibling block before the
     * paired `retain()` lands, or the `backing?.release(); backing = null`
     * two-step exposing a freed pointer to a concurrent reader — then a
     * multi-queue concurrent run over the same [sharedBuf] should either
     * crash or throw "Buffer already released" from [IoBuf.release].
     *
     * The shared buffer's refcount starts at 1 (allocator) + N (per-block
     * retains from `addRange`); each block's `release()` decrements it back
     * to 1 in net. The test thread releases the final ref at the end. Any
     * deviation from this count indicates the race fired.
     */
    @Test
    fun `concurrent addRange and resetForReuse on shared backing IoBuf does not corrupt refcount`() {
        val queueCount = 8
        val queues = Array(queueCount) { idx ->
            dispatch_queue_create("keel.poc.headers-pool.backing-$idx", null)
        }
        val errors = AtomicInt(0)
        val firstErrorPrinted = AtomicInt(0)
        val iterationsPerQueue = 10_000

        // 1024 B = 2^10 satisfies the power-of-two requirement in
        // chainIndexFor; the bytes' content is irrelevant because addRange
        // only stores integer slot positions.
        @Suppress("IoBufLeak")
        val sharedBuf: IoBuf = DefaultAllocator.allocate(1024)
        sharedBuf.writerIndex = 64
        try {
            val group = dispatch_group_create()
            for (q in queues) {
                repeat(iterationsPerQueue) {
                    dispatch_group_enter(group)
                    dispatch_async(q) {
                        try {
                            val headers = HttpHeadersPool.borrow()
                            // Pass the SHARED buf to multiple concurrent
                            // blocks. addRange will `backing = buf; buf.retain()`
                            // for the first call on each fresh HttpHeaders.
                            headers.addRange(
                                buf = sharedBuf,
                                hash = 0x12345678,
                                nameStart = 0,
                                nameLen = 8,
                                valueStart = 16,
                                valueLen = 16,
                            )
                            headers.release() // → resetForReuse → backing.release()
                        } catch (t: Throwable) {
                            if (errors.fetchAndAdd(1) < 5 && firstErrorPrinted.compareAndSet(0, 1)) {
                                println(
                                    "[backing-shared] error: ${t::class.simpleName}: ${t.message}",
                                )
                            }
                        }
                        dispatch_group_leave(group)
                    }
                }
            }

            dispatch_group_wait(group, DISPATCH_TIME_FOREVER)

            val observed = errors.load()
            val totalIterations = queueCount * iterationsPerQueue
            assertEquals(
                expected = 0,
                actual = observed,
                message = "Backing-IoBuf race fired: $observed errors / $totalIterations. " +
                    "Either `backing = buf; buf.retain()` ordering is exposed across queues, " +
                    "or `backing?.release(); backing = null` exposes a freed pointer. " +
                    "Hypothesis (e) confirmed: shared backing IoBuf needs cross-queue protection.",
            )
        } finally {
            // Release the initial allocator-issued reference. If the race
            // had over-released, this call would throw "Buffer already
            // released" — captured as a final invariant check.
            try {
                sharedBuf.release()
            } catch (t: Throwable) {
                fail(
                    "Final release on shared backing IoBuf threw: ${t::class.simpleName}: " +
                        "${t.message}. Net refcount drifted across $queueCount queues × " +
                        "$iterationsPerQueue iterations — direct evidence of refcount race " +
                        "in addRange / resetForReuse.",
                )
            }
        }
    }
}
