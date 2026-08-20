package io.github.fukusaka.keel.core

/**
 * The transport failed, and what it was carrying did not reach the peer.
 *
 * Raised from the calls that wait for a send to land — [Channel.flush] and
 * [Channel.awaitFlushComplete] — whether the wait was already parked when the
 * send was refused or began after the connection had already ended. Which of
 * those a caller was is not something it chose, so it is not something the
 * type depends on. (A pipelined [Channel.flush] *begun* after the connection
 * ended is refused before it waits at all, since flushing a closed channel is
 * a caller error rather than a send that failed. The interface default has no
 * such check and raises this.) [Channel.shutdownOutput] does not raise it at all: it
 * would do so only when the drain happened to run in place, which the caller
 * does not choose either.
 *
 * A pipelined application sees a reported refusal on the handler error
 * path, the layer's own way of being told — before the inactive report,
 * whatever entry met it, so the reason arrives while a handler can still act
 * on it. Three are deliberately not reported there: one met while the
 * caller is already closing, one met after the connection's end was
 * already reported — the peer can end the connection first, and a reason
 * delivered after the end reaches nobody who can act on it — and a second
 * one met while the first is still being reported, since the first is the
 * reason the connection ended. A wait is still answered in all three — with
 * that same refusal in the first two, and with the first one in the third,
 * which is the reason the connection ended. Whether a subtype added later
 * takes the same route is that subtype's to say.
 *
 * **This is not a cancellation, and that distinction is the point.** A
 * caller that closes its own channel gets a `CancellationException`, because
 * ending work it started is exactly what cancellation means and structured
 * concurrency should treat it as such. This type is for the cases the caller
 * did not ask for: the platform refused the send, the engine stopped without
 * being told to, or handling the connection failed and it was ended to
 * contain that. Those are failures to handle, not cancellations to propagate.
 *
 * **Retrying the same operation does not help.** Every subtype means the
 * connection is finished — the bytes are gone and no later attempt on this
 * transport will reach the peer. Catch this type to end the exchange; catch a
 * subtype only when they need different handling. What differs between them
 * is what failed and how far it reaches: one send, this connection, or the
 * engine and every connection on it.
 *
 * Subtypes are exhaustive and stay that way: a new way for a transport to
 * fail belongs here as another subtype, so a caller's `when` keeps compiling
 * against the full set.
 */
public sealed class TransportFailureException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * The platform refused a send outright, and the bytes it was carrying were
 * discarded because they can never reach the peer.
 *
 * A refusal is definitive by the transport seam's own rule: what is merely
 * blocked is reported as such and retried, so anything that reaches here has
 * already been classified as final. The write side of the connection is over.
 *
 * A half-close whose flush ends this way sends no FIN: announcing an orderly
 * end over a stream the peer received truncated would say the exchange
 * finished when it did not. Nor is it raised from the half-close itself,
 * which would answer one way when the drain ran in place and another when it
 * ran on a later tick — a difference the caller neither chose nor can read.
 */
public class RefusedWriteException(
    message: String,
    cause: Throwable? = null,
) : TransportFailureException(message, cause)

/**
 * Handling this connection failed, and it was ended so the failure went no
 * further.
 *
 * What threw is in the cause: work the engine was running on this
 * connection's behalf — a readiness event, a step of its wind-down, a
 * deferred flush. The engine answers such a throw by ending the one
 * connection it belongs to, which is what keeps the rest of them running;
 * [EngineFailureException] is the other scale, where the loop itself is gone
 * and every connection with it.
 *
 * **A wait is how a caller hears about it, not a handler.** The failure has
 * already been through whatever handler chain was serving this connection —
 * often it started there — so it is not sent back down that chain as an
 * error, which would hand a handler its own throw and invite an answer that
 * throws again. It is logged where it was contained, the connection is
 * reported inactive as any ending connection is, and a flush still owed an
 * answer is given this.
 */
public class ConnectionFailureException(
    message: String,
    cause: Throwable? = null,
) : TransportFailureException(message, cause)

/**
 * The engine's event loop ended while this flush was still owed an answer,
 * without having been asked to stop.
 *
 * Distinct from the caller closing the channel or the engine: that is a
 * cancellation, and this is not. Reaching this means the loop is gone for a
 * reason the application did not choose — so every connection it served is
 * gone with it, not just this one. Treat it as a fault to report rather than
 * a connection to retry.
 *
 * A loop that ends by throwing records that on its way out, before it
 * publishes that it has stopped — so a wait ended by the terminal sequence,
 * and one arriving after it, are told the same thing. Without that record the
 * two ways a loop can be gone are indistinguishable, and both used to be
 * delivered as the cancellation that only one of them is.
 *
 * **A loop ending this way usually takes the process with it.** The engines
 * run it as a thread entry point with nothing above it to catch, which is why
 * the readiness dispatch and the task drain guard what they run: a throw that
 * gets past them has no owner left. The waits are ended before that, by the
 * terminal sequence the loop runs on its way out, so a caller does hear this
 * — and an embedding that drives the loop on its own thread hears it for as
 * long as it keeps running.
 */
public class EngineFailureException(
    message: String,
    cause: Throwable? = null,
) : TransportFailureException(message, cause)
