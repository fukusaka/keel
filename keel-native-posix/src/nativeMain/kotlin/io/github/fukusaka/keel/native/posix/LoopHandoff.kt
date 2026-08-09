package io.github.fukusaka.keel.native.posix

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.usleep
import kotlin.concurrent.AtomicInt
import kotlin.time.TimeSource

/**
 * Off-loop → EventLoop hand-off for the POSIX readiness engines (epoll,
 * kqueue). A caller that must run work on the loop thread — or, once the loop
 * has stopped, run a fallback itself — drives it through [runOnLoop]. The loop
 * publishes its shutdown progress through [markFinished] / [markQuiescent].
 *
 * Extracted so the two engines share one implementation: the hand-off has a
 * narrow correctness window (a dispatched task racing the loop's final drain,
 * and the recycled-fd hazard of releasing an fd the loop could still be arming)
 * and a bug fixed in one copy would otherwise have to be fixed in the other by
 * hand — exactly the divergence that made the loop-owned close a two-place fix
 * every time it changed.
 *
 * The dependencies on the loop are injected rather than inherited: [inEventLoop]
 * answers whether the caller is already on the loop thread, and [dispatchToLoop]
 * queues a task for it. Both are plain lambdas so this stays free of the
 * engine's coroutine / cinterop types.
 *
 * **Thread safety**: safe from any thread.
 *
 * @param inEventLoop `true` when the caller already runs on the loop thread.
 * @param dispatchToLoop queues a task to run on the loop thread.
 */
@OptIn(ExperimentalForeignApi::class)
@InternalPosixEventLoopApi
class LoopHandoff(
    private val inEventLoop: () -> Boolean,
    private val dispatchToLoop: (task: () -> Unit) -> Unit,
) {

    // Set once the loop has drained for the last time. A "still running" flag
    // cannot answer "will anything still run my task": it drops the moment
    // close() asks the loop to stop, well before the thread is joined, and it
    // stays set when the loop breaks out on a fatal syscall error. Only this
    // flag means the queue is dead for good.
    private val loopFinished = AtomicInt(0)

    // Set after the final drain, when the loop is guaranteed to run nothing
    // more. [loopFinished] cannot answer this: it is published *before* that
    // drain so a caller can tell its own task will still be picked up, which
    // means the loop may well be mid-task when it reads 1. Anything that has to
    // know the loop is quiet — closing an fd the loop could still arm — must
    // gate on this instead.
    private val loopQuiescent = AtomicInt(0)

    /**
     * Marks that the loop has stopped polling and is about to run its final
     * drain. Publish this **before** the drain, so a caller whose task is
     * already queued reads 0 and lets the drain pick it up.
     */
    fun markFinished() {
        loopFinished.value = 1
    }

    /**
     * Marks that the final drain is complete and the loop will run nothing
     * more. Publish this **after** the drain.
     */
    fun markQuiescent() {
        loopQuiescent.value = 1
    }

    /**
     * Whether the loop has stopped polling — it may still run already-queued
     * work in its final drain, but it will never wait for a kernel event
     * again.
     *
     * The flag for "must I not *park* on this loop": anything waiting to be
     * woken by future readiness waits forever from here on, whereas
     * [isQuiescent] answers the narrower "will my task run at all". A task
     * running inside the final drain reads 1 here and 0 there — exactly the
     * window in which parking is fatal and dispatching is still fine.
     */
    fun isFinished(): Boolean = loopFinished.value == 1

    /**
     * Whether the loop has published quiescence — it will run nothing more,
     * and its own close (which follows) may already have released its wakeup
     * fd. Read by the loop's dispatcher to skip the wakeup write once waking
     * can no longer mean anything.
     */
    fun isQuiescent(): Boolean = loopQuiescent.value == 1

    /**
     * Hands [onLoop] to the loop thread; runs [ifStopped] on the caller if the
     * loop is already gone.
     *
     * Listener teardown uses this so the `close(2)` for a watched fd is issued
     * by the thread that owns the readiness set, never by a caller racing that
     * thread's poll — the shape Netty gets by executing every channel close on
     * its EventLoop. Running the close there also orders it after any
     * registration already queued for the same fd, so a dispatched arm cannot
     * land on a descriptor number the kernel has since handed to someone else.
     *
     * The accept hand-off uses it for the same descriptor hazard read the
     * other way round: a freshly accepted fd carries a number the kernel may
     * just have taken back from a connection on that very worker, so releasing
     * it while the worker still holds a queued arm for the old owner is the
     * same collision. A worker that will run nothing more therefore has the
     * descriptor released here rather than handed to it — Netty's `forceClose`
     * on a rejected `execute`, reached by a hand-off that waits instead of
     * refusing.
     *
     * **On a live loop this returns before the work runs** — waiting would
     * block the caller on a loop that may be mid-syscall. **On a stopping
     * loop it blocks**: a caller landing between [markFinished] and
     * [markQuiescent] spins out the final drain and the stop sweep — which run
     * user code — and then runs [ifStopped] synchronously. A caller that
     * blocks inside that window while holding something the sweep's handlers
     * need turns the wait into a deadlock; close paths must not hold
     * application locks.
     *
     * **[waitBudgetMicros] bounds that block, and the default does not.** The
     * wait is unbounded because giving up early is the recycled-fd hazard
     * itself, which is the right trade for a caller that blocks only its own
     * thread — every close path here does. It is the wrong trade for a caller
     * whose thread other work depends on: the accept hand-off runs on a boss
     * EventLoop, so an unbounded wait there stops that loop wholesale — every
     * listener it serves, every task queued for it (a `close()` teardown
     * dispatched to it included), and its own `pthread_join`. Such a caller
     * passes a budget and accepts the descriptor hazard at expiry rather than
     * trading one worker's failure for the whole engine's. The return value
     * says which happened.
     *
     * The two blocks exist because the fallback runs off the loop. [onLoop] may
     * touch loop-owned state (registries the loop guards), because it only ever
     * runs on the loop thread. [ifStopped] runs on the caller once the loop has
     * stopped — or, for a caller that bounded its wait, while it is still
     * draining — where those registries are moot or about to be, and reading
     * them from another thread buys nothing, so it must be self-contained:
     * releasing the fd is the one thing still required, and that is thread-safe
     * anywhere. (Their lock is safe to take from any thread; it is never
     * destroyed. What the fallback must not do is act on state the loop owned.)
     *
     * Exactly one of the two runs, enforced by a shared CAS, and neither can be
     * missed: the loop publishes [markFinished] before its final drain, so a
     * caller that reads 0 has already been queued for that drain, and one that
     * reads 1 claims the work here. That holds whether or not the wait was cut
     * short — a budget decides how long this waits, never how many blocks run.
     *
     * **Thread safety**: safe from any thread.
     *
     * @param waitBudgetMicros how long to wait out a stopping loop's teardown
     *   before running [ifStopped] anyway. Negative (see [WAIT_UNBOUNDED])
     *   waits for as long as it takes; `0` takes the fallback without waiting
     *   at all, for a caller that has already paid this wait once and will not
     *   pay it again. Only consulted in that one window.
     * @return which block ran, and — when it was the fallback — whether the
     *   wait was cut short to get there. See [HandoffOutcome].
     */
    fun runOnLoop(
        onLoop: () -> Unit,
        ifStopped: () -> Unit = onLoop,
        waitBudgetMicros: Long = WAIT_UNBOUNDED,
    ): HandoffOutcome {
        // Fully stopped: nothing drains the queue again, so offering buys
        // nothing and pins the task's captures in the dead queue for the loop
        // object's lifetime (the dispatcher itself already declines to write
        // the wakeup fd once quiescent). It also keeps loop-owned teardown off
        // a thread that only *looks* like the loop. The readiness loops release
        // their thread id as they exit, so a recycled `pthread_t` no longer
        // reaches [inEventLoop] as a match for them -- but [inEventLoop] is
        // supplied by the caller, and this gate holds without depending on that
        // release. Running the teardown on such a thread would act on a ledger
        // that is swept, closed and no longer that thread's to touch -- the
        // loop it belonged to is gone. A caller in
        // the narrower window — the loop finished but not yet quiescent —
        // keeps the offer path below: the final drain still picks its task
        // up, on the loop.
        if (loopQuiescent.value == 1) {
            ifStopped()
            return HandoffOutcome.FELL_BACK
        }
        if (inEventLoop()) {
            onLoop()
            return HandoffOutcome.HANDED_TO_LOOP
        }
        val claimed = AtomicInt(0)
        dispatchToLoop {
            if (claimed.compareAndSet(0, 1)) onLoop()
        }
        // Reading 0 here means this offer preceded the write, so the final
        // drain is guaranteed to pick it up — nothing more to do, and the
        // common path (a live loop) never waits.
        if (loopFinished.value == 0) return HandoffOutcome.HANDED_TO_LOOP

        // The loop is shutting down. Wait out its final drain before deciding:
        // [loopFinished] is published *before* that drain, so acting on it
        // alone could close this fd while the loop is still arming it from a
        // queued registration — the recycled-fd hazard this function exists to
        // avoid.
        //
        // What is waited on is no longer only already-queued work: the loop
        // also ends its stranded waiters on the way out, which runs their
        // cancellation handlers and then the coroutines those resume. So this
        // waits on application code, ending when that code does rather than
        // after some number of queued tasks — which is why a caller whose
        // thread others depend on hands in a budget instead.
        //
        // Any negative budget is unbounded, not just the sentinel: a computed
        // value that goes negative reads as "no limit", which is the side that
        // keeps the ordering rather than the side that quietly drops it.
        val start = TimeSource.Monotonic.markNow()
        var expired = false
        while (loopQuiescent.value == 0) {
            if (waitBudgetMicros >= 0 && start.elapsedNow().inWholeMicroseconds >= waitBudgetMicros) {
                expired = true
                break
            }
            usleep(LOOP_QUIESCE_POLL_MICROS)
        }
        // The loop got there first: it owns the work and this caller reports
        // nothing, whether or not its own wait had run out.
        if (!claimed.compareAndSet(0, 1)) return HandoffOutcome.HANDED_TO_LOOP
        ifStopped()
        return if (expired) HandoffOutcome.FELL_BACK_AFTER_EXPIRY else HandoffOutcome.FELL_BACK
    }

    companion object {
        /**
         * Passed as `waitBudgetMicros` to wait out the loop's teardown however
         * long it takes. Any negative value means the same; this one names it.
         */
        const val WAIT_UNBOUNDED: Long = -1L

        // Poll interval while waiting out the loop's final drain. The wait is
        // rare (only a close racing shutdown) and ends when that teardown does,
        // so a short sleep keeps it responsive without busy-spinning.
        private const val LOOP_QUIESCE_POLL_MICROS: UInt = 50u
    }
}
