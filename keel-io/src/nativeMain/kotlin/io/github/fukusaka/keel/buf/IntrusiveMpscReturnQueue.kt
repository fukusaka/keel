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
 * **Atomic close.** [head] holds `null`, a [NativeIoBuf] chain head, or the
 * [closed] sentinel. Because the close-vs-enqueue decision is a single atomic on
 * [head], a release that loses the race to [close] sees the sentinel and frees its
 * own backing (instead of stranding the buffer in a queue nobody drains). A buffer
 * is therefore *either* emitted by [close] *xor* rejected by [offer] returning
 * `false` — never both, never neither.
 *
 * **Concurrency.** [offer] is safe from any number of producer threads (CAS loop
 * on the atomic head). [drain] and [close] are **single-consumer only** — both run
 * on the owning EventLoop thread and never overlap each other (the drain hooks and
 * `onClose` are all owner-thread-confined). On Kotlin/Native
 * `AtomicReference.compareAndSet` / `getAndSet` is a single CPU atomic versus the
 * contended spin lock's busy-wait.
 *
 * ```
 * freeing thread A:  offer(buf1) → CAS head            → true
 * freeing thread B:  offer(buf2) → CAS head            → true
 * owning EventLoop:  drain(out)  → [buf1, buf2] (FIFO)
 * owning EventLoop:  close(out)  → swaps head to closed; later offer → false
 * ```
 */
internal class IntrusiveMpscReturnQueue {

    /**
     * Distinct sentinel meaning "owner closed; the queue is never drained again".
     * One per queue, allocated at construction — never touched on the hot path. A
     * separate object identity (rather than a fake [NativeIoBuf]) keeps the
     * "real buffer or marker?" test a cheap `=== closed` reference compare.
     */
    private val closed = Any()

    /** `null`, a [NativeIoBuf] chain head, or [closed]. Widened to `Any?` so the
     *  close-vs-offer decision is a single atomic on this one cell. */
    private val head = AtomicReference<Any?>(null)

    /**
     * Enqueues [buf] unless the queue is closed. Returns `false` iff [closed] was
     * observed, in which case the caller **must free the buffer's backing itself**
     * — a closed queue is never drained. Lock-free; safe from any producer thread.
     * The buffer must not be in any other freelist or queue (refcount is zero).
     */
    fun offer(buf: NativeIoBuf): Boolean {
        while (true) {
            val cur = head.value
            if (cur === closed) return false
            buf.nextLink = cur as NativeIoBuf? // null or a real chain head
            if (head.compareAndSet(cur, buf)) return true
        }
    }

    /**
     * Drains all buffers in FIFO order into [out] (single-consumer only — the
     * owning EventLoop thread). Atomically swaps the head to null and reverses the
     * LIFO push chain back to insertion order. A no-op once [close] has run (the
     * head is the sentinel); the owner never drains after close anyway.
     */
    fun drain(out: MutableList<IoBuf>) {
        while (true) {
            val h = head.value
            // Empty, or closed: leave the head untouched. A plain getAndSet(null)
            // would momentarily clear the sentinel, opening a window where a
            // producer's offer could land on a transiently-null head and then be
            // overwritten when the sentinel is restored. CAS the observed chain to
            // null instead, so a closed head is never disturbed and a racing offer
            // simply makes this CAS retry.
            if (h == null || h === closed) return
            if (head.compareAndSet(h, null)) {
                emitReversed(h as NativeIoBuf, out)
                return
            }
        }
    }

    /**
     * Atomically closes the queue and emits whatever was still enqueued into [out]
     * (single-consumer only — the owning EventLoop thread, exactly once from
     * [SlabAllocator.onClose]). After this, every [offer] returns `false` (its
     * caller frees the backing) and [drain] is a no-op. Combined with the fact
     * that `closed` is set on the allocator *before* this runs, every buffer is
     * either emitted here or freed by its own `offer == false` path — never both.
     */
    fun close(out: MutableList<IoBuf>) {
        val h = head.getAndSet(closed)
        if (h == null || h === closed) return
        emitReversed(h as NativeIoBuf, out)
    }

    /** Non-empty check used to skip an empty drain (false once closed). */
    fun isNotEmpty(): Boolean {
        val h = head.value
        return h != null && h !== closed
    }

    /** Reverses the singly-linked LIFO chain into FIFO [out], clearing each link. */
    private fun emitReversed(headNode: NativeIoBuf, out: MutableList<IoBuf>) {
        var node: NativeIoBuf? = headNode
        var reversed: NativeIoBuf? = null
        while (node != null) {
            val next = node.nextLink
            node.nextLink = reversed
            reversed = node
            node = next
        }
        var cur = reversed
        while (cur != null) {
            val next = cur.nextLink
            cur.nextLink = null
            out.add(cur)
            cur = next
        }
    }
}
