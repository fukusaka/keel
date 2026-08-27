@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import kotlinx.coroutines.CancellationException

/**
 * Why a join did not take.
 *
 * [AbstractReadinessEventLoop.joinLoop] answers with one of these instead of a
 * bare `false`, because the two differ in what the caller should do and only
 * the loop can tell them apart. A construction site that re-derived the answer
 * — by reading the loop's finishing flag after the join returned — would be
 * guessing: that flag is published before the sweep, so a refusal seen during a
 * final drain would read as a sweep.
 */
@InternalReadinessEngineApi
enum class JoinRefusal {
    /**
     * The loop had already swept, so it refused to register anything.
     *
     * Nothing it is asked for afterwards will be served, which is why an accept
     * loop that meets this ends rather than retrying.
     */
    LOOP_STOPPED,

    /**
     * The registration took and the kernel then refused the arm, so the loop
     * gave the join back.
     *
     * The loop is running and keeps serving everyone else: this is one
     * connection's failure, not the engine's.
     */
    ARM_REFUSED,
}

/**
 * How a [JoinRefusal] reads in the failure a construction site raises.
 *
 * One wording for every site, so the same cause does not acquire a different
 * name depending on which of them met it. The loop logs the detail — the
 * syscall, the descriptor and the errno — on its way to taking the join back;
 * this is the half that travels to the caller.
 *
 * `null` is not reachable from a site that checks the join first, since a join
 * that did not take always carries a reason. It is worded anyway rather than
 * asserted: a diagnostic is the wrong place to add a way to fail.
 */
internal fun joinRefusalReason(refusal: JoinRefusal?): String = when (refusal) {
    JoinRefusal.LOOP_STOPPED -> "its EventLoop had stopped"
    JoinRefusal.ARM_REFUSED -> "its EventLoop could not arm the connection with the kernel"
    null -> "it could not join its EventLoop"
}

/**
 * What a Channel-mode accept raises for a connection whose join did not take.
 *
 * The type decides the accept loop's fate, not just the message: `AcceptLoop`
 * rethrows [CancellationException] and ends, and logs anything else before
 * backing off and retrying. So a swept loop is raised as a cancellation —
 * nothing accepted afterwards could be served — while a refused arm is not: the
 * loop is running, the failure belongs to the one connection whose descriptor
 * has already gone back, and ending the whole accept loop over it would take a
 * healthy server off the air.
 */
internal fun acceptJoinFailure(refusal: JoinRefusal?): Throwable {
    val message = "accept dropped this connection: ${joinRefusalReason(refusal)}"
    return if (refusal == JoinRefusal.ARM_REFUSED) {
        IllegalStateException(message)
    } else {
        CancellationException(message)
    }
}
