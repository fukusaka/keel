package io.github.fukusaka.keel.buf

/**
 * A per-size-class freelist of recyclable [IoBuf]s, used by [PooledAllocator].
 *
 * This is the pluggable concurrency seam of the pool: the allocator's
 * size-class lookup is common (a lock-free [PooledAllocator] table), but the
 * freelist underneath each class has platform- and workload-specific
 * thread-safety needs, so it is selected per allocator.
 *
 * **Why an interface (measured)**: the per-op dispatch overhead of calling
 * through this interface versus a concrete final type is ~0.07 ns (noise) on the
 * allocation hot path — the op is dominated by its atomic/lock, so a vtable
 * dispatch is negligible (see `benchmark --bench=freelist-dispatch`). Making the
 * freelist pluggable therefore costs nothing measurable.
 *
 * **Selection** (measured trade-offs):
 * - **spin lock + array** (Native default): fast uncontended, ABA-immune;
 *   busy-waits under contention. Fits keel's EL-pinned engines.
 * - **intrusive Treiber** (JVM default): fast uncontended and lock-free, but
 *   ABA-unsafe for reused nodes under genuine MPMC — safe only because the JVM
 *   engines are EL-pinned and never truly contended.
 * - **mutex** (parks waiters): slower uncontended, far better contended, avoids
 *   the userspace-spinlock preemption pathology. Fits an arbitrary-concurrency
 *   allocator.
 * - **versioned-index** (lock-free, ABA-safe): a documented escalation.
 *
 * **Thread safety**: implementations declare their own guarantees. The
 * allocator calls [push] / [pop] on its owning EventLoop thread on the hot path;
 * cross-thread release (e.g. NWConnection on Native) requires a thread-safe
 * implementation.
 *
 * **Ownership**: [pop] returns a buffer whose reference count is zero (ready for
 * reuse); the allocator resets it and installs the pool owner. [push] receives a
 * buffer whose reference count has already reached zero. An implementation that
 * cannot accept the buffer (pool full) returns `false` from [push] so the
 * allocator frees the backing instead.
 */
interface Freelist {
    /**
     * Offers [buf] back to the freelist. Returns `true` if retained for reuse,
     * `false` if the pool is full (the caller must free the backing).
     */
    fun push(buf: IoBuf): Boolean

    /** Removes and returns a recyclable buffer, or `null` if the freelist is empty. */
    fun pop(): IoBuf?

    /**
     * Appends all currently pooled buffers to [out] without removing them.
     *
     * Used at startup to enumerate pooled buffers (e.g. io_uring fixed-buffer
     * registration). Not a hot-path operation.
     */
    fun snapshotInto(out: MutableList<IoBuf>)
}
