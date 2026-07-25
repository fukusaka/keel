package io.github.fukusaka.keel.native.posix

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.usleep
import kotlin.concurrent.AtomicInt

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
     * Hands [onLoop] to the loop thread; runs [ifStopped] on the caller if the
     * loop is already gone. Does not wait for either to finish.
     *
     * Listener teardown uses this so the `close(2)` for a watched fd is issued
     * by the thread that owns the readiness set, never by a caller racing that
     * thread's poll — the shape Netty gets by executing every channel close on
     * its EventLoop. Running the close there also orders it after any
     * registration already queued for the same fd, so a dispatched arm cannot
     * land on a descriptor number the kernel has since handed to someone else.
     *
     * **This returns before the work runs.** `close()` is asynchronous by
     * contract for exactly this reason: waiting here would block the caller on
     * a loop that may be mid-syscall, and the engines backed by io_uring or a
     * `Selector` cannot offer a synchronous release either.
     *
     * The two blocks exist because the fallback runs off the loop. [onLoop] may
     * touch loop-owned state (registries the loop guards), because it only ever
     * runs on the loop thread. [ifStopped] runs on the caller once the loop has
     * stopped, where those registries are moot and their lock may already be
     * destroyed by close, so it must be self-contained — releasing the fd is
     * the one thing still required, and that is thread-safe anywhere.
     *
     * Exactly one of the two runs, enforced by a shared CAS, and neither can be
     * missed: the loop publishes [markFinished] before its final drain, so a
     * caller that reads 0 has already been queued for that drain, and one that
     * reads 1 claims the work here.
     *
     * **Thread safety**: safe from any thread.
     */
    fun runOnLoop(onLoop: () -> Unit, ifStopped: () -> Unit = onLoop) {
        if (inEventLoop()) {
            onLoop()
            return
        }
        val claimed = AtomicInt(0)
        dispatchToLoop {
            if (claimed.compareAndSet(0, 1)) onLoop()
        }
        // Reading 0 here means this offer preceded the write, so the final
        // drain is guaranteed to pick it up — nothing more to do, and the
        // common path (a live loop) never waits.
        if (loopFinished.value == 0) return

        // The loop is shutting down. Wait out its final drain before deciding:
        // [loopFinished] is published *before* that drain, so acting on it
        // alone could close this fd while the loop is still arming it from a
        // queued registration — the recycled-fd hazard this function exists to
        // avoid. The wait is bounded by the drain, which only runs already
        // queued work.
        while (loopQuiescent.value == 0) {
            usleep(LOOP_QUIESCE_POLL_MICROS)
        }
        if (claimed.compareAndSet(0, 1)) {
            ifStopped()
        }
    }

    private companion object {
        // Poll interval while waiting out the loop's final drain. The wait is
        // rare (only a close racing shutdown) and bounded by already-queued
        // work, so a short sleep keeps it responsive without busy-spinning.
        private const val LOOP_QUIESCE_POLL_MICROS: UInt = 50u
    }
}
