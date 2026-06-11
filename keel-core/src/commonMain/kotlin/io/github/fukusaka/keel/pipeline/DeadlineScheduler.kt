package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.logging.warn

/**
 * EventLoop-confined deadline timer backing [EventLoopTimer] for the wait-loop
 * engines (epoll / kqueue / io_uring / nio). The EventLoop drives its
 * `epoll_wait` / `kevent` wait timeout from [nextDeadlineMillis] and calls
 * [expireDue] after each wake.
 *
 * **Data structure — per-duration FIFO timer list (O(1), scalable).** Timers that
 * share a delay expire in the order they were last touched, so each distinct
 * delay keeps an intrusive doubly-linked list ordered by deadline: [schedule] and
 * [TimerHandle.touch] append/move the node to the tail (O(1)), the list head is
 * always the next to expire (O(1) [nextDeadlineMillis]), and [expireDue] pops the
 * expired prefix from the head (O(k), k = actually expired). No per-connection
 * sweep, no `O(log n)` heap push on every read, no separate timer thread — the
 * cost is independent of the connection count and the read rate.
 *
 * This fits the idle/read-timeout access pattern (a handful of distinct delays,
 * touched on every read) far better than a hashed timing wheel, whose strengths
 * (arbitrary per-timer delays on a shared, approximate, fixed-tick thread) are the
 * opposite of keel's per-EventLoop, deadline-driven, exact-wake model. If a future
 * feature needs arbitrary set-once absolute deadlines, add a per-EventLoop min-heap
 * alongside (still EventLoop-local, still exact-wake) rather than a wheel.
 *
 * **Thread safety**: none — confined to the owning EventLoop thread, like the rest
 * of the allocator/transport bookkeeping. [nowMillis] must be a monotonic clock.
 *
 * @param nowMillis monotonic current-time source in milliseconds.
 * @param logger sink for the warn emitted when a timer task throws (see
 *   [expireDue]). Defaults to a no-op logger for source compatibility;
 *   engines should pass their EventLoop logger so a throwing task is
 *   traceable.
 */
class DeadlineScheduler(
    private val nowMillis: () -> Long,
    private val logger: Logger = NoopLoggerFactory.logger("DeadlineScheduler"),
) : EventLoopTimer {
    /** Intrusive timer node; also the public [TimerHandle]. */
    private class Node(val delayMillis: Long, val task: () -> Unit) : TimerHandle {
        var deadline: Long = 0
        var prev: Node? = null
        var next: Node? = null

        /** The list this node currently lives in, or null once fired/cancelled. */
        var owner: DeadlineScheduler? = null

        override fun touch() {
            owner?.touchNode(this)
        }

        override fun cancel() {
            owner?.removeNode(this)
        }
    }

    /** One intrusive FIFO list (head = earliest deadline) keyed by shared delay. */
    private class Bucket {
        var head: Node? = null
        var tail: Node? = null
    }

    private val buckets = HashMap<Long, Bucket>()

    override fun schedule(delayMillis: Long, task: () -> Unit): TimerHandle {
        require(delayMillis > 0) { "delayMillis must be positive, was $delayMillis" }
        val node = Node(delayMillis, task)
        node.deadline = nowMillis() + delayMillis
        node.owner = this
        addTail(buckets.getOrPut(delayMillis) { Bucket() }, node)
        return node
    }

    private fun touchNode(node: Node) {
        val bucket = buckets[node.delayMillis] ?: return
        node.deadline = nowMillis() + node.delayMillis
        // Same delay ⇒ the refreshed deadline is the latest, so moving to the tail
        // keeps the bucket ordered by deadline.
        unlink(bucket, node)
        addTail(bucket, node)
    }

    private fun removeNode(node: Node) {
        val bucket = buckets[node.delayMillis] ?: return
        unlink(bucket, node)
        node.owner = null
    }

    /**
     * Earliest pending deadline in absolute [nowMillis] units, or [Long.MAX_VALUE]
     * if no timers are scheduled. The EventLoop turns this into a relative wait
     * timeout (`deadline - now`, floored at 0); when it is [Long.MAX_VALUE] the
     * EventLoop blocks indefinitely (no idle wake-ups).
     */
    fun nextDeadlineMillis(): Long {
        // Fast path for the common "no timeouts configured" case: avoid allocating
        // a values iterator on every EventLoop iteration (this runs in the hot loop).
        if (buckets.isEmpty()) return Long.MAX_VALUE
        var min = Long.MAX_VALUE
        for (bucket in buckets.values) {
            val head = bucket.head ?: continue
            if (head.deadline < min) min = head.deadline
        }
        return min
    }

    /**
     * Fires every timer whose deadline is at or before [now], in deadline order,
     * removing it first so its [task] may re-[schedule]. A throwing task is
     * caught and warn-logged: timers on one scheduler belong to many
     * connections, so one connection's throwing deadline task must neither
     * kill the EventLoop thread nor skip the remaining due timers of the
     * same sweep (the same per-item isolation rule as the engine-side
     * callback guards).
     */
    fun expireDue(now: Long) {
        // Fast path: nothing scheduled — avoid the values-iterator allocation that
        // would otherwise occur on every EventLoop wake (this runs in the hot loop).
        if (buckets.isEmpty()) return
        for (bucket in buckets.values) {
            while (true) {
                val head = bucket.head ?: break
                if (head.deadline > now) break
                unlink(bucket, head)
                head.owner = null
                try {
                    head.task()
                } catch (t: Throwable) {
                    logger.warn(t) { "deadline timer task threw; remaining due timers continue" }
                }
            }
        }
    }

    private fun addTail(bucket: Bucket, node: Node) {
        val tail = bucket.tail
        node.prev = tail
        node.next = null
        if (tail == null) bucket.head = node else tail.next = node
        bucket.tail = node
    }

    private fun unlink(bucket: Bucket, node: Node) {
        val prev = node.prev
        val next = node.next
        if (prev == null) bucket.head = next else prev.next = next
        if (next == null) bucket.tail = prev else next.prev = prev
        node.prev = null
        node.next = null
    }
}
