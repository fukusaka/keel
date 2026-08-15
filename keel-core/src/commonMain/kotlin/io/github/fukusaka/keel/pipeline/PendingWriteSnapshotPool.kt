package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.pipeline.AbstractIoTransport.PendingWrite

/**
 * Reusable free-list of [ArrayList]<[PendingWrite]> ownership-snapshot
 * containers, for [AbstractIoTransport] subclasses whose write completion
 * is asynchronous relative to `flush()` returning (Netty's `ChannelFuture`,
 * io_uring's CQE-based submission).
 *
 * **Why this exists**: `flush()` must transfer ownership of the current
 * `pendingWrites` entries to an async completion callback before the deque
 * can be reused by the next `write()`/`flush()` generation — otherwise a
 * later `write()` mutating the live deque would corrupt the in-flight
 * callback's view of what to release. The straightforward fix,
 * `ArrayList(pendingWrites)`, allocates a fresh list on every `flush()`
 * call. This pool reuses those lists instead.
 *
 * **Not a fixed-size double-buffer**: under backpressure (a slow peer),
 * multiple `flush()` generations can have callbacks pending simultaneously
 * — the OS/kernel hasn't acknowledged an earlier write when a later one is
 * issued. A 2-slot ping-pong would let a later `flush()` overwrite a list
 * still referenced by an earlier callback. This pool has no fixed capacity:
 * [borrow] takes a free list if one exists, or allocates fresh only when
 * the free-list is empty (i.e. when more generations are in flight than
 * have ever been returned before). Steady-state (one generation at a time,
 * the common case) settles at zero allocation after the first flush cycle.
 *
 * **Not needed by synchronous-completion engines**: epoll, kqueue, NIO, and
 * Node.js transports mutate `pendingWrites` directly (the readiness
 * transport re-offsets a partial-write remainder in place at the head,
 * NIO removes and re-enqueues it) and never call [borrow] — their
 * write completion is synchronous relative to the syscall, so there is
 * only ever one write-readiness registration outstanding, nothing to
 * snapshot.
 *
 * **Thread safety**: not thread-safe. Callers must only use this from the
 * owning transport's EventLoop thread, matching the single-thread
 * invariant already documented on [AbstractIoTransport.pendingWrites].
 */
class PendingWriteSnapshotPool {

    private val free = ArrayDeque<ArrayList<PendingWrite>>()

    /**
     * Returns a list (reused from the free-list, or freshly allocated if
     * none is free) populated with a snapshot of [source]'s current
     * contents. The caller owns the returned list until it passes it back
     * via [recycle].
     */
    fun borrow(source: ArrayDeque<PendingWrite>): ArrayList<PendingWrite> {
        val snapshot = free.removeLastOrNull()?.apply { clear() } ?: ArrayList(source.size)
        snapshot.addAll(source)
        return snapshot
    }

    /**
     * Returns a list obtained from [borrow] back to the free-list once its
     * async completion callback has fired and it is no longer referenced.
     * Clears the list's contents (dropping [PendingWrite] references so
     * they don't outlive their [PendingWrite.buf] release) before pooling.
     */
    fun recycle(snapshot: ArrayList<PendingWrite>) {
        snapshot.clear()
        free.addLast(snapshot)
    }
}
