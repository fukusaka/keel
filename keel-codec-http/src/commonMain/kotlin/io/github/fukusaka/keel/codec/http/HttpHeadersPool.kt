package io.github.fukusaka.keel.codec.http

/**
 * Stack-based pool of reusable [HttpHeaders] instances.
 *
 * Reduces per-request allocation by recycling the [HttpHeaders] object
 * and the two underlying `LinkedHashMap` bucket arrays across requests.
 * A pool hit costs one stack pop and a `LinkedHashMap.clear()` on each
 * map (entries GC'd, bucket array kept); a miss (cold start or pool
 * drained) costs one fresh [HttpHeaders] construction.
 *
 * **Design** — minimal and intentionally simple:
 *
 * - One global stack capped at [MAX_POOLED]. Beyond the cap, released
 *   instances are dropped (let the GC reclaim them); a runaway leak
 *   cannot grow the pool unbounded.
 * - No size classes / no eviction strategy. The pool just holds onto
 *   whatever the two maps' buckets grew to over the borrower's
 *   lifetime. For browser-typical workloads (≤ 16 unique header names)
 *   the steady-state bucket capacity converges within the first dozen
 *   requests on a connection.
 * - **Thread-confined**: this is a `kotlin.collections.ArrayDeque` with
 *   no internal locking. keel-server-http currently runs each
 *   connection on a single EventLoop thread; the parser borrow and
 *   handler release both happen on that thread. If multi-threaded use
 *   is introduced later this must move to a per-EventLoop pool (the
 *   pattern keel already uses for `PooledDirectAllocator`) or guard
 *   with `synchronized` / a lock-free Treiber stack.
 *
 * **Not** a buffer allocator: codec headers never participate in
 * zero-copy DMA, so the pool deliberately holds heap-managed
 * `LinkedHashMap` instances and does not reach into the I/O
 * `BufferAllocator`. Every other production HTTP codec we surveyed
 * (Netty 4.1, Jetty 12, Hyper, Ktor CIO) follows the same boundary —
 * the codec layer never pulls header storage from the I/O buffer pool.
 */
internal object HttpHeadersPool {

    private val stack: ArrayDeque<HttpHeaders> = ArrayDeque()

    /**
     * Returns a reset [HttpHeaders] ready for `add`. Either a pooled
     * instance (with its `LinkedHashMap` bucket arrays already
     * allocated) or a fresh construction if the pool is empty.
     */
    fun borrow(): HttpHeaders {
        val pooled = if (stack.isNotEmpty()) stack.removeLast() else null
        if (pooled != null) return pooled
        return HttpHeaders().also { it.markPooled() }
    }

    /**
     * Returns [headers] to the pool. Called from
     * [HttpHeaders.release] after [HttpHeaders.resetForReuse] has
     * wiped the per-request entries. Callers must not retain the
     * reference.
     *
     * If the pool is at [MAX_POOLED] capacity the instance is dropped
     * (eligible for GC), bounding the pool's memory footprint.
     */
    fun giveBack(headers: HttpHeaders) {
        if (stack.size >= MAX_POOLED) return
        stack.addLast(headers)
    }

    /** Visible for tests — drops every pooled instance. */
    internal fun clear() {
        stack.clear()
    }

    /** Visible for tests — number of currently-pooled instances. */
    internal fun size(): Int = stack.size

    /**
     * Cap on the number of instances retained simultaneously. Sized
     * for keel-server-http where each EventLoop thread serves multiple
     * concurrent in-flight connections; 64 covers typical request /
     * response concurrency without holding more than ~16 KiB of pooled
     * header storage at steady state.
     */
    private const val MAX_POOLED: Int = 64
}
