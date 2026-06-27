@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.buf

/**
 * Native [Freelist] backed by an `ArrayDeque` guarded by a [NativeMutex]
 * (`pthread_mutex`).
 *
 * **When to choose this**: an *arbitrary-concurrency* allocator instance — one
 * that may receive concurrent pop/push from threads not pinned to a single
 * `EventLoop`. Compared to the default `SpinLockFreelist`:
 * - Uncontended cost is higher (about 2× the spin lock; `pthread_mutex_lock`
 *   takes its own CAS fast-path, plus parking infrastructure).
 * - Under genuine contention the mutex *parks* waiters instead of busy-waiting,
 *   so it scales flat and avoids the userspace-spinlock preemption pathology
 *   (a preempted lock holder no longer burns waiters' CPU).
 *
 * See `benchmark --bench=freelist-variants` / `--bench=freelist-contended`
 * for the numbers; the keel default (EL-pinned engines) keeps the spin lock
 * because uncontended is the dominant regime, but a public allocator that
 * promises arbitrary-thread safety should pick this implementation.
 *
 * **ABA safety**: structural — there is no CAS, so the classic Treiber-stack
 * ABA hazard does not apply.
 *
 * ## Lifecycle
 *
 * The `pthread_mutex_t` lifecycle lives in [NativeMutex] (allocated from
 * `nativeHeap` at construction, released on [close]). [PooledAllocator.close]
 * invokes [close] after draining pooled buffers so the destroy lands when the
 * mutex is guaranteed quiescent. After [close] the freelist must not be used.
 */
class MutexFreelist(private val maxSlots: Int) : Freelist {
    private val list = ArrayDeque<IoBuf>(maxSlots)
    private val mutex = NativeMutex()

    override fun push(buf: IoBuf): Boolean = mutex.withLock {
        if (list.size < maxSlots) {
            list.addLast(buf)
            true
        } else {
            false
        }
    }

    override fun pop(): IoBuf? = mutex.withLock {
        if (list.isEmpty()) null else list.removeLast()
    }

    override fun size(): Int = mutex.withLock { list.size }

    override fun snapshotInto(out: MutableList<IoBuf>) {
        mutex.withLock { out.addAll(list) }
    }

    /**
     * Destroys the underlying mutex (via [NativeMutex.close]). Idempotent.
     *
     * The caller (typically [PooledAllocator.close]) must ensure no other thread
     * is mid-lock when this runs. After [close] all freelist methods are undefined;
     * the allocator's closed-flag guard prevents them from being reached on the
     * documented teardown path.
     */
    override fun close() = mutex.close()
}
