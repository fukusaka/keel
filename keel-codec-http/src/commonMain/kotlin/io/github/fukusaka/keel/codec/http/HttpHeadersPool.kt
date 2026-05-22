package io.github.fukusaka.keel.codec.http

/**
 * Thread-local pool of reusable [HttpHeaders] instances.
 *
 * Reduces per-request allocation by recycling the [HttpHeaders] object
 * **and** its internal storage (the `ArrayList<HeaderEntry>` backing
 * array + the `IntArray` hash bucket head + the `IntArray` per-entry
 * bucket-chain links) across requests. A pool hit costs one stack pop;
 * a miss (cold start or pool drained) costs one fresh [HttpHeaders]
 * construction. The recycled instance is reset by
 * [HttpHeaders.release] before it returns here.
 *
 * **Thread-local, not global.** keel's multi-worker EventLoop engines
 * (NIO / epoll / io_uring) round-robin accepted connections across a
 * `workerGroup` of `availableProcessors()` threads by default; each
 * connection's parser `borrow`s and the handler `release`s on that
 * connection's owning EventLoop thread. A single global pool backed by
 * a `kotlin.collections.ArrayDeque` corrupts under the resulting
 * concurrent `borrow` / `release` (the `ArrayDeque` is not thread-safe;
 * the symptom was `ArrayIndexOutOfBoundsException` killing worker
 * threads). The pool is therefore backed by a per-thread stack via
 * [headersPoolStack] (`java.lang.ThreadLocal` on JVM, `@ThreadLocal`
 * on Native, a plain singleton on single-threaded JS).
 *
 * Per-thread is equivalent to per-EventLoop here (keel confines each
 * EventLoop to one thread), so the recycling benefit is preserved:
 * each EventLoop thread reuses instances across all the connections it
 * serves, with zero locking and zero cross-thread contention. A
 * strategy microbench (`HttpHeadersPoolStrategyBenchmark`) on a 32-core
 * box measured the thread-local approach at ~376 M cycles/s with the
 * full alloc reduction retained, versus ~3.7 M cycles/s for a
 * lock-guarded global pool (contention collapse) at the same thread
 * count. The choice of `@ThreadLocal` is validated on Kotlin/Native
 * pthread-created threads — the actual EventLoop thread mechanism — by
 * `NativeConcurrencyProbeTest`.
 *
 * **Memory bound.** Total pooled storage is
 * `MAX_POOLED × per-instance footprint × EventLoop-thread count`. The
 * per-instance footprint is dominated by the retained
 * `bucketHead: IntArray(64)` (~272 B) plus the grown `bucketNext` /
 * `entries` backing arrays — roughly ~600 B at the CDN-typical header
 * count. With [MAX_POOLED] = 64 and `availableProcessors()` worker
 * threads this is about `64 × 600 B × N` (≈ 1.2 MiB on a 32-core box).
 * [MAX_POOLED] caps each thread's stack; sizing follows Netty's
 * `Recycler` model (per-thread cap, total scales with thread count),
 * though keel's value is far smaller because Netty's 4096 default is
 * for tiny `ByteBuf` handles whereas each [HttpHeaders] is ~600 B and
 * a single EventLoop thread's working set is the connections it serves
 * concurrently, not thousands.
 *
 * **Not** a buffer allocator: codec headers never participate in
 * zero-copy DMA, so the pool deliberately holds heap-managed objects
 * and does not reach into the I/O `BufferAllocator`. Every other
 * production HTTP codec we surveyed (Netty 4.1, Jetty 12, Hyper,
 * Ktor CIO) follows the same boundary — the codec layer never pulls
 * header storage from the I/O buffer pool.
 */
internal object HttpHeadersPool {

    /**
     * Returns a reset [HttpHeaders] ready for `add`. Either a pooled
     * instance (with its `ArrayList` backing + `IntArray` hash bucket
     * already allocated) or a fresh construction if this thread's pool
     * is empty.
     */
    fun borrow(): HttpHeaders {
        val stack = headersPoolStack()
        return if (stack.isEmpty()) {
            HttpHeaders().also { it.markPooled() }
        } else {
            stack.removeLast()
        }
    }

    /**
     * Returns [headers] to the calling thread's pool. Called from
     * [HttpHeaders.release] after [HttpHeaders.resetForReuse] has wiped
     * the per-request state. Callers must not retain the reference.
     *
     * If this thread's pool is at [MAX_POOLED] capacity the instance is
     * dropped (eligible for GC), bounding the per-thread footprint.
     */
    fun giveBack(headers: HttpHeaders) {
        val stack = headersPoolStack()
        if (stack.size < MAX_POOLED) stack.addLast(headers)
    }

    /** Visible for tests — drops the calling thread's pooled instances. */
    internal fun clear() {
        headersPoolStack().clear()
    }

    /** Visible for tests — number of instances pooled on the calling thread. */
    internal fun size(): Int = headersPoolStack().size

    /**
     * Cap on the number of instances retained per EventLoop thread.
     * The working set of one EventLoop thread is the number of
     * connections it serves with an in-flight request head at once; 64
     * covers typical per-thread connection concurrency. Total pooled
     * memory is this value times the worker-thread count (see the
     * class KDoc memory-bound note).
     */
    internal const val MAX_POOLED: Int = 64
}

/**
 * Returns the calling thread's own [HttpHeaders] pool stack. The
 * returned deque is confined to the calling thread, so [HttpHeadersPool]
 * operates on it without any locking. Implemented per platform:
 * `java.lang.ThreadLocal` (JVM), a `@ThreadLocal` top-level value
 * (Native, per pthread), and a plain singleton (single-threaded JS).
 */
internal expect fun headersPoolStack(): ArrayDeque<HttpHeaders>
