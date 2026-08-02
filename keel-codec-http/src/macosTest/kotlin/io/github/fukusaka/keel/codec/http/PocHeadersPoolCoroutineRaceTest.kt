package io.github.fukusaka.keel.codec.http

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_specific
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_set_specific
import platform.darwin.dispatch_queue_t
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Synthetic cross-queue header-pool repro — hypothesis (a)/(f): does a **suspending coroutine**
 * on a GCD serial-queue-backed [CoroutineDispatcher] interleave
 * [HttpHeadersPool.borrow] / [HttpHeaders.release] in a way that confuses
 * pool ownership across worker-thread migrations?
 *
 * **Why this scenario is different from the tight-loop synthetics.** The
 * sibling `PocHeadersPoolGcdRaceTest` in this module drives borrow/release
 * inside `dispatch_async { ... }` blocks. Each block is atomic — no
 * suspension between borrow and release. The cross-queue header-pool race
 * in production involves **HttpServerHandler** coroutines that:
 *
 * 1. Parse a request → `HttpHeadersPool.borrow()` returns h1 on worker A.
 * 2. Hand h1 to user handler logic.
 * 3. User handler `suspend`s on some I/O (DB lookup, downstream HTTP call,
 *    response body emission).
 * 4. GCD migrates the connection's serial queue blocks across the worker
 *    thread pool while the coroutine sits in the dispatcher's task queue.
 * 5. Coroutine resumes — now possibly on worker B — and eventually calls
 *    `h1.release()` (often via `try { } finally { }` cleanup).
 *
 * The borrow happened on worker A's `@ThreadLocal nativeStack`; the release
 * pushes onto B's. The sibling test showed this fragmentation does NOT
 * crash by itself. What it might do, under keep-alive pipelining or
 * HTTP/1.1 connection reuse, is allow a **subsequent request on the same
 * connection** to borrow from B's pool and obtain an instance that some
 * other (still suspended) handler is mid-mutation on.
 *
 * **Inline dispatcher.** This test deliberately does not depend on the
 * production `NwConnectionQueueDispatcher` (which lives in
 * `keel-engine-nwconnection` and would create a layering cycle for a
 * codec-http test). Instead we inline a minimal one with the same
 * `dispatch_queue_set_specific` + `dispatch_get_specific` queue-identity
 * pattern Apple recommends, so the test exercises the exact production
 * concurrency model — coroutines confined to a serial GCD queue via
 * `dispatch_async`, with the `isDispatchNeeded` fast path for resumption
 * already on the queue.
 *
 * **Limitations.** Even this test does not reproduce HTTP keep-alive's
 * connection-level state machine (request N's response writer racing
 * request N+1's parser). Reproducing the full keep-alive scenario
 * synthetically requires wiring an actual `HttpServerHandler` against a
 * fake transport, which approaches re-implementing the production server.
 * If this test also passes, the cross-queue header-pool race's residual 3% almost certainly requires
 * the full I/O + handler + response-writer choreography to fire — i.e. it
 * is a timing-sensitive intermittent bug that reproduces only under real
 * NWConnection I/O, where adding instrumentation perturbs the timing
 * window enough to hide it.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
class PocHeadersPoolCoroutineRaceTest {

    private val budget = 30.seconds

    /**
     * Minimal in-test mirror of `NwConnectionQueueDispatcher` from
     * `keel-engine-nwconnection`. Uses queue identity (`dispatch_queue_set_specific`
     * / `dispatch_get_specific`) so a coroutine already on the queue can
     * resume in-place without an extra `dispatch_async` hop — the exact
     * production semantics.
     */
    private class SerialGcdDispatcher(
        private val queue: dispatch_queue_t,
    ) : CoroutineDispatcher() {
        private val markerRef: StableRef<Any> = StableRef.create(Any())
        private val marker: COpaquePointer = markerRef.asCPointer()

        init {
            dispatch_queue_set_specific(queue, marker, marker, null)
        }

        override fun isDispatchNeeded(context: CoroutineContext): Boolean =
            dispatch_get_specific(marker) != marker

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatch_async(queue) {
                block.run()
            }
        }

        fun close() {
            markerRef.dispose()
        }
    }

    /**
     * Many concurrent coroutines on a single GCD-serial-backed dispatcher,
     * each doing borrow → `add` → `yield` → `add` → `delay` → release.
     * The dispatcher serialises resumption blocks, but GCD's worker pool
     * migrates them across pthreads. Asserts no exception bubbles up from
     * any coroutine.
     */
    @Test
    fun `coroutines on serial GCD dispatcher with delay survive borrow release cycle`() =
        runBlocking {
            withTimeout(budget) {
                val queue = dispatch_queue_create("keel.poc.coroutine-race.serial", null)
                    ?: error("dispatch_queue_create returned null")
                val dispatcher = SerialGcdDispatcher(queue)
                val errors = AtomicInt(0)
                val firstError = CompletableDeferred<Throwable>()
                val coroutineCount = 500
                val cyclesPerCoroutine = 50

                try {
                    val jobs = mutableListOf<Job>()
                    repeat(coroutineCount) {
                        val job = launch(dispatcher) {
                            try {
                                repeat(cyclesPerCoroutine) {
                                    val headers = HttpHeadersPool.borrow()
                                    headers.add("X-Coroutine", "stage-1")
                                    // Yield: gives other coroutines a chance to
                                    // run on the same dispatcher between borrow
                                    // and release.
                                    yield()
                                    headers.add("X-Coroutine-2", "stage-2")
                                    // Real-ish suspend with deadline; mimics
                                    // a tiny async I/O. The coroutine may
                                    // resume on a different GCD worker.
                                    delay(1)
                                    headers.release()
                                }
                            } catch (t: Throwable) {
                                errors.fetchAndAdd(1)
                                firstError.complete(t)
                            }
                        }
                        jobs.add(job)
                    }

                    jobs.forEach { it.join() }
                } finally {
                    dispatcher.close()
                }

                val observed = errors.load()
                if (observed > 0 && firstError.isCompleted) {
                    val first = firstError.getCompleted()
                    println("[coroutine-race] first error: ${first::class.simpleName}: ${first.message}")
                }
                assertEquals(
                    expected = 0,
                    actual = observed,
                    message = "Coroutine + dispatcher + suspending borrow/release threw " +
                        "$observed times across $coroutineCount coroutines × $cyclesPerCoroutine " +
                        "cycles. Suggests the cross-queue header-pool race fires when borrow and release straddle " +
                        "a suspension point — hypothesis (a)/(f) confirmed in isolation.",
                )
            }
        }
}
