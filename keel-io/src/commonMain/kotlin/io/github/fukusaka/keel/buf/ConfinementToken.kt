package io.github.fukusaka.keel.buf

/**
 * Identifies the execution context that *owns* a pooled allocator, so a release
 * can be classified as same-owner — take the freelist fast path — or
 * cross-context — route through the owner's cross-thread return queue.
 *
 * The owner is a *confinement domain*, not necessarily a single OS thread:
 * - Thread-pinned engines (epoll / kqueue / io_uring / nio) own the allocator on
 *   one pthread. [ThreadIdConfinement] (the default) captures that thread id on
 *   the first allocate and compares against it.
 * - A serial-dispatch-queue engine (NWConnection on GCD) is confined to one queue
 *   but migrates across worker pthreads, so a thread-id comparison would
 *   misclassify same-queue releases. Such an engine installs a token backed by
 *   the queue identity instead.
 *
 * This replaces the earlier boolean "disable cross-thread routing" opt-out. That
 * opt-out turned routing *off* for serial-queue engines — every release took the
 * freelist path — which silently mis-handles a genuinely off-queue release (e.g.
 * a pull-mode `asSource` refill that releases on the caller's thread rather than
 * the connection's queue: that release would race the queue's freelist instead of
 * being funnelled back to it). A token models the queue confinement precisely, so
 * an off-context release is still routed to the owner; only the genuinely
 * on-context releases take the fast path.
 *
 * **Thread safety**: [isCurrentContextOwner] is read on every release (any
 * thread); implementations must answer correctly from any thread. [captureOwner]
 * is called on every allocate, which by contract runs on the owning context.
 */
interface ConfinementToken {
    /**
     * Called on every [BufferAllocator.allocate] — which by contract runs on the
     * owning context. A token that captures its owner lazily (e.g. the first
     * allocate's thread id) latches here; a token whose owner is fixed at
     * construction (e.g. a dispatch-queue marker) leaves this a no-op.
     */
    fun captureOwner() {}

    /**
     * Whether the calling thread is currently executing on the owning context.
     * `true` lets the caller take the freelist fast path; `false` means the
     * release must be routed to the owner's return queue.
     */
    fun isCurrentContextOwner(): Boolean
}
