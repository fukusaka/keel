@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.buf

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * JVM [Freelist] backed by an `ArrayDeque` guarded by a `ReentrantLock`.
 *
 * **When to choose this**: an *arbitrary-concurrency* allocator instance — one
 * that may receive concurrent pop/push from threads not pinned to a single
 * `EventLoop`. Compared to the default intrusive `TreiberStackFreelist`:
 * - Uncontended cost is slightly higher (about 1.5× the Treiber lock-free path;
 *   `ReentrantLock.lock` takes its own CAS fast-path, plus parking infrastructure).
 * - Under genuine MPMC the mutex *parks* waiters instead of CAS-retrying or
 *   busy-waiting, so it scales flat. Crucially, the mutex is
 *   **ABA-immune**, while the Treiber stack of reused nodes is **not** —
 *   `benchmark --bench=freelist-contended` (and `FreelistContendedBenchmark`)
 *   reproduces the Treiber drain dropping to 1/64 nodes under contention.
 *
 * The keel default JVM allocator keeps the Treiber stack because the JVM
 * engines (NIO, Netty) hold their pool instances EL-pinned and never truly
 * contend, so ABA does not fire and the lock-free path is faster. A public
 * allocator that promises arbitrary-thread safety should pick this
 * implementation.
 *
 * ## Lifecycle
 *
 * The `ReentrantLock` is a plain Java object with no native resources, so it
 * is reclaimed by the GC together with the freelist itself. Unlike the Native
 * counterpart, no explicit destroy is required.
 */
class MutexFreelist(private val maxSlots: Int) : Freelist {
    private val list = ArrayDeque<IoBuf>(maxSlots)
    private val lock = ReentrantLock()

    override fun push(buf: IoBuf): Boolean = lock.withLock {
        if (list.size < maxSlots) {
            list.addLast(buf)
            true
        } else {
            false
        }
    }

    override fun pop(): IoBuf? = lock.withLock {
        if (list.isEmpty()) null else list.removeLast()
    }

    override fun size(): Int = lock.withLock { list.size }

    override fun snapshotInto(out: MutableList<IoBuf>) {
        lock.withLock { out.addAll(list) }
    }
}
