package io.github.fukusaka.keel.pipeline

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

/**
 * Holds dispatched work until a test asks for it, so queued pipeline work
 * (a deferred journal drain, a close walk handed to the loop) is observable.
 *
 * Answers `isDispatchNeeded` with the default `true`, which is what the
 * readiness engines answer: the pipeline defers its journal drain onto it
 * instead of draining inline, so a test can assemble a whole chain before
 * the first lifecycle sweep runs — the shape under which a throwing handler
 * hides the handlers below it from that sweep.
 *
 * [runQueued] drains until nothing is left rather than running one round:
 * off the owning thread the outbound walk queues *each hop* — every
 * `propagateClose` goes back through the pipeline's dispatch — so a single
 * round runs the tail and leaves the rest of the chain behind.
 */
internal class QueueingDispatcher : CoroutineDispatcher() {
    private val queued = ArrayDeque<Runnable>()

    /** Run once before the queued work, to put the transport on its loop. */
    var onRun: (() -> Unit)? = null

    val pending: Int get() = queued.size

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queued.addLast(block)
    }

    fun runQueued() {
        onRun?.invoke()
        while (queued.isNotEmpty()) queued.removeFirst().run()
    }
}
