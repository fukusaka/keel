package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.core.EngineFailureException
import io.github.fukusaka.keel.core.TransportFailureException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resumeWithException

// What a caller waiting on a flush is told when the flush will never be
// answered.
//
// Two of the ways it can end are failures: the connection ended for a reason
// nobody asked for, or the loop that would have drained the queue is gone.
// Each is delivered as itself, because delivering a failure as a cancellation
// makes an application choose between swallowing real cancellations and
// letting a dead connection or a dead engine cancel its scope.
//
// The cancellation is what remains when no failure was recorded: the caller
// closed its own channel, which is work it started and asked to end. What a
// wait is told about an ending that is neither — one the application's own
// configuration brought about, such as an idle timeout reclaiming a connection
// nobody is using — is decided where that ending happens, by whether it
// records anything here.
//
// Here rather than in the transport because these are decisions, not steps: no
// queue, no continuation slot, no readiness state — the reason that was
// recorded goes in, the answer comes out. Which also means the two can be read
// side by side, and they have to be: they say the same thing about the same
// contract, one state apart.

/**
 * Ends [cont] for a transport that is closed, given the reason that was
 * recorded when it was forced.
 *
 * A close the caller asked for records nothing, and gets the cancellation. A
 * close the *transport* forced — because a send was refused, or because
 * handling the connection failed and the engine ended it — records why, and
 * that is what the wait is told. Which failures record during a close the
 * caller asked for is not uniform, and deliberately: a refused send records
 * whenever it is the first failure, because a waiter asked whether its bytes
 * reached the peer and they did not; everything else defers to the close,
 * because the caller ended the connection and something failing while it did
 * is not what ended it. So a cancellation from here means nothing was
 * recorded as ending this connection — not that nothing went wrong.
 *
 * **A wait gets the same answer whichever side of the drain it began on.**
 * One already parked is resumed with the refusal by the drain that met it;
 * one arriving afterwards finds a closed transport and is given the same
 * refusal here. Which of the two a caller was is not something it chose or
 * can read, so it cannot be what decides the type.
 *
 * **A refusal recorded during a close the caller asked for still answers this
 * way**, even though `close()`'s own caller is deliberately not told about it.
 * The two are asking different questions: `close()` asked to end the
 * connection and discard what was queued, so a dead peer met while discarding
 * is the outcome it asked for; a flush wait asked whether its bytes reached
 * the peer, and they did not.
 */
internal fun endWaitForClosedTransport(
    cont: CancellableContinuation<Unit>,
    fd: Int,
    recorded: TransportFailureException?,
) {
    if (recorded != null) {
        cont.resumeWithException(recorded)
    } else {
        cont.cancel(CancellationException("transport closed before the pending flush on fd=$fd could drain"))
    }
}

/**
 * Ends [cont] for a loop that will never run its flush, given what ended that
 * loop — or `null` if it stopped because it was asked to.
 *
 * **Two ways a loop can be gone, and only one of them is the caller's doing.**
 * A loop that was asked to stop ends the work its callers started. A loop that
 * ended on its own was asked for nothing: every connection it served is gone,
 * this caller's included, so what it gets is a fault to report rather than a
 * cancellation to propagate. Throwing is only the rarest of the ways it can
 * happen — a poll the kernel refuses for good, or a lock that stopped being
 * exclusive, ends a loop just as finally and records the same thing.
 *
 * Reading the loop's record is safe from the states a caller reaches this
 * through — quiescent, and finishing — because the loop publishes the record
 * before either of them.
 */
internal fun endWaitForStoppedLoop(
    cont: CancellableContinuation<Unit>,
    fd: Int,
    loopFailure: Throwable?,
) {
    if (loopFailure != null) {
        cont.resumeWithException(
            EngineFailureException(
                "the EventLoop ended on its own before the pending flush on fd=$fd could drain",
                loopFailure,
            ),
        )
    } else {
        cont.cancel(CancellationException("EventLoop stopped before the pending flush on fd=$fd could drain"))
    }
}
