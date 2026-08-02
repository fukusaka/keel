package io.github.fukusaka.keel.buf

import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.fail

/**
 * Wall-clock bounds and daemon workers for the allocator concurrency tests.
 *
 * These tests drive real threads through lock-free structures, so a defect in what
 * they exercise shows up as a thread that never finishes. Two things then have to be
 * true for that to arrive as a test failure rather than as silence.
 *
 * **Every wait needs a bound.** An unbounded `join()` or `await()` turns a wedged
 * worker into a test that never returns, which reports nothing at all — no assertion
 * message, no indication of which test was running.
 *
 * **And the workers must be daemons.** A bound alone is not enough: a non-daemon
 * worker keeps the JVM alive after the test method has failed and returned, so the
 * fork never exits and the build waits on it. That is not hypothetical — a CI run of
 * this module hung for twenty-one minutes with no test report written at all and was
 * killed by the job timeout, leaving orphaned JVMs behind. Identifying the module
 * afterwards took reading the archived JUnit XML to find which one had produced no
 * results. With daemon workers the stuck thread cannot hold the fork open, so the
 * suite finishes and names the failing test itself.
 *
 * The budget is deliberately generous. These tests take under two seconds on a
 * developer machine; the bound exists to catch a hang, not to police throughput on a
 * loaded CI runner. It does sit above the build's own slow-test advisory, so a timeout
 * failure also draws the "name it *Benchmark and add @Ignore" warning — misleading
 * here, but only ever printed alongside the real failure it accompanies.
 */
internal const val WORKER_BUDGET_MS = 30_000L

/**
 * A worker thread that cannot outlive the test JVM.
 *
 * [name] appears in the failure message when this thread is the one still running,
 * which is the only clue available once a hang has been converted into a failure.
 */
internal fun workerThread(name: String, body: () -> Unit): Thread =
    Thread(body, name).apply { isDaemon = true }

/**
 * Starts every thread, then waits for all of them within one shared [budgetMs].
 *
 * The budget spans the whole set rather than each thread, so N workers cannot stack
 * up N times the bound. Threads still running when it expires fail the test by name.
 *
 * Failing does not by itself make teardown safe — a caller closing an allocator in a
 * `finally` reaches that `finally` on exactly this path, with the worker still using
 * it. [tearDownWhenStopped] is what handles that.
 */
internal fun Collection<Thread>.startAndJoinWithin(what: String, budgetMs: Long = WORKER_BUDGET_MS) {
    forEach { it.start() }
    joinAllWithin(what, budgetMs)
}

/** Waits for every thread within one shared [budgetMs], failing by name on those still alive. */
internal fun Collection<Thread>.joinAllWithin(what: String, budgetMs: Long = WORKER_BUDGET_MS) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
    forEach { thread ->
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (remainingMs > 0) thread.join(remainingMs)
    }
    val stuck = filter { it.isAlive }
    if (stuck.isNotEmpty()) {
        val names = stuck.joinToString { it.name }
        fail("$what: ${stuck.size} of $size worker(s) still running after ${budgetMs}ms: $names")
    }
}

/** Waits for the latch within [budgetMs], failing rather than blocking forever. */
internal fun CountDownLatch.awaitWithin(what: String, budgetMs: Long = WORKER_BUDGET_MS) {
    if (!await(budgetMs, TimeUnit.MILLISECONDS)) {
        fail("$what: latch was not released within ${budgetMs}ms")
    }
}

/**
 * Spins until [condition] holds, failing rather than spinning forever.
 *
 * Used where a worker waits for another worker to make progress. That wait is
 * unbounded by nature: if the other worker dies early the condition never becomes
 * true, and the spin — not the dead worker — is what hangs the JVM.
 */
internal fun awaitCondition(what: String, budgetMs: Long = WORKER_BUDGET_MS, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
    while (!condition()) {
        // Re-read once the budget is gone: the condition can turn true between the
        // last read and the deadline, and failing without looking again would report
        // a hang that had already resolved.
        if (deadline - System.nanoTime() <= 0) {
            if (condition()) return
            fail("$what: condition never held within ${budgetMs}ms")
        }
        Thread.yield()
    }
}

/**
 * Hands [items] to the queue within one shared [budgetMs].
 *
 * One budget for the whole batch, for the same reason [joinAllWithin] uses one: a
 * per-item bound lets N items stack up N times the wait, which is what an unbounded
 * wait was replaced to avoid.
 */
internal fun <T : Any> BlockingQueue<T>.offerAllWithin(
    items: List<T>,
    what: String,
    budgetMs: Long = WORKER_BUDGET_MS,
) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
    items.forEachIndexed { index, item ->
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (remainingMs <= 0 || !offer(item, remainingMs, TimeUnit.MILLISECONDS)) {
            val position = "item ${index + 1} of ${items.size}"
            fail("$what: could not enqueue $position within ${budgetMs}ms — nothing is draining")
        }
    }
}

/**
 * Runs [teardown] only once every thread here has actually stopped.
 *
 * `PooledAllocator.close()` documents a single-threaded teardown — "engines stop their
 * EventLoop threads before invoking close, so there are no concurrent allocate calls".
 * A bounded join that fails reaches the caller's `finally` with a worker still inside
 * `allocate`, which breaks exactly that contract, and in the shared-arena tests it
 * would tear down the arena whose corruption the test exists to detect.
 *
 * So on the stuck path the allocator is deliberately left open. That costs direct
 * memory the GC still reclaims, in a run that has already failed — cheaper than
 * closing the structure under test out from under a live thread, and it keeps the
 * post-mortem state intact.
 */
internal fun Collection<Thread>.tearDownWhenStopped(teardown: () -> Unit) {
    if (none { it.isAlive }) teardown()
}
