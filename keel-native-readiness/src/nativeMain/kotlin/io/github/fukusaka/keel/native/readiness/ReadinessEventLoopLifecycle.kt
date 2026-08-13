package io.github.fukusaka.keel.native.readiness

/**
 * The thread lifecycle a readiness event loop owns.
 *
 * Separate from [AbstractReadinessEventLoop] rather than declared on it:
 * the loop base has test doubles that drive its ledger and its sweep without
 * ever owning a thread, and making them implement a lifecycle they do not have
 * would be a lie the compiler asks for. What needs the pair is
 * [AbstractReadinessEventLoopGroup], which starts and closes what it holds.
 */
public interface ReadinessEventLoopLifecycle {
    /** Starts this loop's thread. */
    public fun start()

    /** Stops this loop's thread and releases what it holds. */
    public fun close()
}
