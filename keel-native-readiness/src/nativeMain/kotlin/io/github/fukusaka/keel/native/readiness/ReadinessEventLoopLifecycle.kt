package io.github.fukusaka.keel.native.readiness

/**
 * The thread lifecycle a readiness event loop owns.
 *
 * [AbstractReadinessEventLoop] implements this, so every loop owes both halves.
 * It is a named interface rather than two bare members because
 * [AbstractReadinessEventLoopGroup] drives them as a pair — it starts what it
 * builds and closes what it holds — and the pairing is the contract: a loop
 * that starts a thread and cannot stop it leaks it.
 *
 * The base declaring it means the test doubles must supply both. Their [start]
 * is a no-op — they drive the ledger and the sweep directly and never own a
 * thread — but their [close] is not: the base allocates the gather scratch in
 * its constructor, so every loop has something to give back.
 */
@InternalReadinessEngineApi
public interface ReadinessEventLoopLifecycle {
    /** Starts this loop's thread. */
    public fun start()

    /** Stops this loop's thread and releases what it holds. */
    public fun close()
}
