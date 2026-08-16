package io.github.fukusaka.keel.pipeline

/**
 * The write side of a connection is over: a send the platform definitively
 * refused, with the queued bytes discarded because they can never reach the
 * peer.
 *
 * Raised by a transport's flush rather than answered as a completed one —
 * a write that did not happen is not a flush that completed, and the parked
 * waiter, the completion callback and a deferred FIN would all be told
 * otherwise. Transient conditions never reach here: a transport resolves
 * those itself, retrying what is retryable and deferring what is merely
 * blocked to write readiness.
 *
 * **Named so that "the peer is gone" can be told apart from "our own
 * bookkeeping broke".** Both are [IllegalStateException] in this tree, and
 * two callers need the distinction: [AbstractIoTransport.shutdownOutputOwned]
 * gives up its deferred FIN for this one only — a refused *release* still
 * wrote the bytes, so that stream has an orderly end to announce — and a
 * transport's teardown reports it rather than carrying it to `close()`'s
 * caller, because discarding the unsent data is what `close` documents it
 * does.
 *
 * An [IllegalStateException] so a caller that already catches the shape a
 * definitive syscall failure raises sees no change.
 */
public class RefusedWriteException(message: String) : IllegalStateException(message)
