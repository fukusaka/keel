package io.github.fukusaka.keel.buf

import kotlin.concurrent.AtomicReference

/**
 * Lock-free intrusive Multi-Producer Single-Consumer queue for returning pooled
 * [NativeIoBuf]s to their owning allocator from a foreign (non-EventLoop) thread.
 *
 * This is the cross-thread return path of the allocator's mimalloc-style
 * `thread_free` split: when a buffer's refcount reaches zero on a thread other
 * than the one that allocated it, the owning [SlabAllocator] enqueues it here with
 * a lock-free CAS push instead of contending on its per-EventLoop
 * `SpinLockFreelist`. The owning EventLoop thread drains the queue in batch on its
 * allocate slow-path / trim cadence and returns the buffers to the per-class pool
 * on its own thread, so the freelist and its counters stay single-writer.
 *
 * **Intrusive, alloc-free.** Unlike the generic [MpscQueue], this stores the next
 * pointer directly in [NativeIoBuf.nextLink] (a field reserved for exactly this),
 * so an `offer` allocates nothing — there is no wrapper node and therefore no GC
 * pressure on the cross-thread release path, which is the whole point of the
 * sharded-return design.
 *
 * **Concurrency.** [offer] is safe from any number of producer threads (CAS loop
 * on the atomic head). [drain] is **single-consumer only** — it must run on the
 * owning EventLoop thread. On Kotlin/Native `AtomicReference.compareAndSet` is a
 * single CPU CAS (~5–10 ns) versus the contended spin lock's busy-wait.
 *
 * ```
 * freeing thread A:  offer(buf1) → CAS head
 * freeing thread B:  offer(buf2) → CAS head
 * owning EventLoop:  drain(out)  → [buf1, buf2] (FIFO)
 * ```
 */
internal class IntrusiveMpscReturnQueue {

    private val head = AtomicReference<NativeIoBuf?>(null)

    /**
     * Enqueues [buf] (lock-free, any thread). Prepends to the head via CAS,
     * threading the link through [NativeIoBuf.nextLink]. The buffer must not be in
     * any other freelist or queue (its refcount is already zero).
     */
    fun offer(buf: NativeIoBuf) {
        while (true) {
            val cur = head.value
            buf.nextLink = cur
            if (head.compareAndSet(cur, buf)) return
        }
    }

    /**
     * Drains all buffers in FIFO order into [out] (single-consumer only — the
     * owning EventLoop thread). Atomically swaps the head to null, then reverses
     * the LIFO push chain to restore insertion order, clearing each `nextLink` so
     * the buffer carries no stale link back into the per-class pool.
     */
    fun drain(out: MutableList<IoBuf>) {
        val h = head.getAndSet(null) ?: return
        // Reverse the singly-linked LIFO chain into FIFO.
        var node: NativeIoBuf? = h
        var reversed: NativeIoBuf? = null
        while (node != null) {
            val next = node.nextLink
            node.nextLink = reversed
            reversed = node
            node = next
        }
        // Emit in FIFO order, clearing the intrusive link as we go.
        var cur = reversed
        while (cur != null) {
            val next = cur.nextLink
            cur.nextLink = null
            out.add(cur)
            cur = next
        }
    }

    /** Approximate non-empty check (single-consumer; used to skip an empty drain). */
    fun isNotEmpty(): Boolean = head.value != null
}
