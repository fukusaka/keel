package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.buf.ConfinementToken
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import platform.darwin.dispatch_get_specific
import platform.darwin.dispatch_queue_set_specific
import platform.darwin.dispatch_queue_t

/**
 * [ConfinementToken] for a pooled allocator confined to an NWConnection serial
 * dispatch [queue]. NWConnection serialises every read / write / state-change
 * callback (and, via [NwConnectionQueueDispatcher], every coroutine resumption)
 * on this queue, but GCD migrates the work across worker pthreads — so a thread-id
 * comparison would misclassify a same-queue release as cross-thread.
 *
 * The token tags [queue] with a unique [marker] and answers
 * [isCurrentContextOwner] by `dispatch_get_specific(marker) == marker`, the
 * standard "am I on this queue?" check (Apple's documented replacement for the
 * deprecated `dispatch_get_current_queue`). So an on-queue release takes the
 * allocator's freelist fast path regardless of which worker pthread runs it, while
 * a genuinely off-queue release — e.g. a pull-mode `asSource` refill releasing on
 * the caller's coroutine thread — reports `false` and is routed through the
 * allocator's cross-thread return queue back to the owner. This is the precise
 * model the earlier blanket "disable cross-thread routing" opt-out lacked: that
 * opt-out sent *every* release to the freelist, so an off-queue release raced the
 * queue's freelist instead of being funnelled to it.
 *
 * An independent [marker] from [NwConnectionQueueDispatcher]'s (which keeps its own
 * for dispatch elision): both ask the same question of the same queue, but keeping
 * the allocator's confinement decoupled from dispatch elision avoids coupling the
 * two concerns. A queue specific is a single cheap slot, so the second tag is
 * negligible.
 *
 * **Lifetime**: [markerRef] is intentionally never disposed — its lifetime matches
 * the token, which matches the connection's allocator child, which matches the
 * queue; the leak is bounded by connection count and reclaimed at process exit
 * (same rationale as [NwConnectionQueueDispatcher]'s marker).
 */
@OptIn(ExperimentalForeignApi::class)
internal class NwQueueConfinement(queue: dispatch_queue_t) : ConfinementToken {
    private val markerRef: StableRef<Any> = StableRef.create(Any())
    private val marker: COpaquePointer = markerRef.asCPointer()

    init {
        dispatch_queue_set_specific(queue, marker, marker, null)
    }

    // The owner is the queue, fixed at construction — nothing to latch per-allocate.
    override fun isCurrentContextOwner(): Boolean = dispatch_get_specific(marker) == marker
}
