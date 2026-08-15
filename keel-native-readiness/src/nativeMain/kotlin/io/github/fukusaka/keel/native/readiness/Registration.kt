package io.github.fukusaka.keel.native.readiness

import kotlinx.coroutines.CancellableContinuation

/**
 * A pending I/O interest for a file descriptor.
 *
 * Multiple [Registration]s with the same `(fd, interest)` key form a
 * singly-linked FIFO chain via [next]. The chain head doubles as the map
 * entry; the head's [tail] field tracks the chain tail so append is O(1)
 * without per-key allocation. Non-head nodes ignore [tail].
 *
 * **Mutability**: [next] / [tail] are mutated only under the registration
 * mutex. No `@Volatile` because all access is lock-guarded.
 *
 * @param fd The file descriptor to watch.
 * @param interest What the waiter is waiting for.
 * @param continuation Resumed when the fd becomes ready.
 */
class Registration internal constructor(
    val fd: Int,
    val interest: Interest,
    val continuation: CancellableContinuation<Unit>,
    /**
     * Runs when the loop had an answer for this waiter and could not
     * deliver it — the resumption goes through the waiter's own
     * dispatcher, which may refuse the work.
     *
     * The waiter is unreachable by then: it left the ledger when the
     * answer was taken, so nothing later can find it, and the answer that
     * failed is the only one this loop had. A waiter that owns something
     * for the duration of its wait — the connect path owns its descriptor
     * — releases it here. On most routes that is the only release that
     * runs; on the stop sweep the waiter's own cancellation handler runs
     * first, because a cancelled continuation runs its handlers before the
     * resumption the dispatcher then refuses. Both call the same release,
     * whose one-shot claim admits one of them. `null` for a waiter that
     * owns nothing: an `accept()` caller's server fd belongs to the
     * server.
     *
     * Called at most once, and guarded by its callers. On the loop thread
     * for every route but one: a loop closed without ever running does its
     * terminal sequence on the closing thread, and the sweep there reaches
     * this too. One route reaches nothing, this hook included: a stop
     * sweep skipped because a failed mutex release left the registration
     * lock stuck (its ERROR log says so; ending that skip is tracked as
     * its own design task).
     */
    internal val onUndeliverable: (() -> Unit)? = null,
) {
    internal var next: Registration? = null
    internal var tail: Registration? = null
}
