package io.github.fukusaka.keel.io

import kotlin.concurrent.AtomicReference

/**
 * Lock-free Multi-Producer Single-Consumer (MPSC) queue.
 *
 * **Producers** ([offer]) use CAS on the atomic head to enqueue items
 * without mutex locks. Multiple threads can call [offer] concurrently.
 *
 * **Consumer** ([drain]) atomically swaps the head to null and reverses
 * the resulting linked list to restore FIFO order. Only one thread
 * (the EventLoop thread) should call [drain].
 *
 * This eliminates `pthread_mutex_lock/unlock` overhead on the dispatch
 * hot path. On Kotlin/Native, `AtomicReference.compareAndSet` compiles
 * to a single CPU CAS instruction (~5-10ns) vs mutex (~50-100ns).
 *
 * ```
 * Thread A (producer):  offer(task1) → CAS head
 * Thread B (producer):  offer(task2) → CAS head
 * EventLoop (consumer): drain() → [task1, task2] (FIFO)
 * ```
 */
class MpscQueue<T> {

    private class Node<T>(val value: T, var next: Node<T>? = null)

    private val head = AtomicReference<Node<T>?>(null)

    /**
     * Enqueues an item (lock-free, thread-safe).
     *
     * Uses CAS loop to prepend to the head. Retry on contention
     * is rare in practice (EventLoop dispatch is typically from
     * 1-2 threads).
     */
    fun offer(item: T) {
        val node = Node(item)
        while (true) {
            val cur = head.value
            node.next = cur
            if (head.compareAndSet(cur, node)) return
        }
    }

    /**
     * Drains all items in FIFO order (single-consumer only).
     *
     * Atomically swaps the head to null, then reverses the linked
     * list to restore insertion order. Returns an empty list if
     * the queue is empty.
     */
    fun drain(out: MutableList<T>) {
        val h = head.getAndSet(null) ?: return
        // Reverse the singly-linked list (LIFO → FIFO)
        var node: Node<T>? = h
        var reversed: Node<T>? = null
        while (node != null) {
            val next = node.next
            node.next = reversed
            reversed = node
            node = next
        }
        // Collect in FIFO order
        var cur = reversed
        while (cur != null) {
            out.add(cur.value)
            cur = cur.next
        }
    }

    /**
     * Returns true if the queue has items (approximate, non-atomic).
     * Safe for single-consumer use (EventLoop timeout decision).
     */
    fun isNotEmpty(): Boolean = head.value != null
}
