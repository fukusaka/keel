package io.github.fukusaka.keel.core

/**
 * The transport failed, and what it was carrying did not reach the peer.
 *
 * Raised from the calls that wait for a send to land — [Channel.flush] and
 * [Channel.awaitFlushComplete] — and delivered to pipeline handlers through
 * their error path, which is where a handler-only application sees it.
 *
 * **This is not a cancellation, and that distinction is the point.** A
 * caller that closes its own channel gets a `CancellationException`, because
 * ending work it started is exactly what cancellation means and structured
 * concurrency should treat it as such. This type is for the cases the caller
 * did not ask for: the platform refused the send, or the engine stopped
 * without being told to. Those are failures to handle, not cancellations to
 * propagate.
 *
 * **Retrying the same operation does not help.** Both subtypes mean the
 * connection is finished — the bytes are gone and no later attempt on this
 * transport will reach the peer. Catch this type to end the exchange; catch a
 * subtype only when the two need different handling.
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
 * Also the type a transport reads to decide what to do with a half-close it
 * had deferred: a FIN announces an orderly end, which is not true of a stream
 * the peer received truncated, so a refused flush withholds it.
 */
public class RefusedWriteException(
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
 */
public class EngineFailureException(
    message: String,
    cause: Throwable? = null,
) : TransportFailureException(message, cause)
