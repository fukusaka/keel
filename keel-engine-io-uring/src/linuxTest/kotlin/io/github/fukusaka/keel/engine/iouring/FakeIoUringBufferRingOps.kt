package io.github.fukusaka.keel.engine.iouring

import io_uring.io_uring
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * In-memory [IoUringBufferRingOps] that lets tests script kernel
 * ring setup / free outcomes and inspect the buffer publish path
 * (`addBuffer` / `advance`). Single-threaded — only safe to drive from
 * the test thread.
 *
 * `setupBufRing` / `freeBufRing` consult a FIFO of scripted failures;
 * when the queue is empty they apply the happy-path default.
 * `addBuffer` / `advance` never fail (the underlying liburing calls are
 * shared-memory writes) — they only record their arguments so a test
 * can assert that initialisation staged the expected buffers.
 *
 * The native `ring` / `addr` arguments are accepted to satisfy the
 * interface but ignored — the fake keys all state off its own model.
 */
@OptIn(ExperimentalForeignApi::class)
internal class FakeIoUringBufferRingOps(
    /**
     * Optional CQE-drain progress source. When supplied (typically as
     * `{ ring.cqesDrainedCount }` against a companion [FakeIoUringRing]),
     * every [advance] call records the current drain progress in
     * [advanceEvents], so tests can assert "the engine returned N buffer
     * slots after K CQEs were consumed" — the retention-window invariant
     * Phase C / C3 needs. Defaults to `{ 0 }` for tests that only care
     * about counts and bid layout.
     */
    private val cqeDrainProgress: () -> Int = { 0 },
) : IoUringBufferRingOps {

    /**
     * A single `addBuffer` invocation, for publish-path assertions
     * (bid + ring offset only). For payload-level assertions that need
     * the kernel-visible (addr, len) pair, use [addedBuffers].
     */
    data class AddCall(val bid: Int, val offset: Int)

    /**
     * Full record of one `addBuffer` invocation including the buffer
     * payload (addr + len) published to the kernel ring. Tests that
     * simulate a CQE-with-buffer flow use [addedBuffers] (or
     * [bufferForBid]) to recover the bytes the engine staged for a
     * given bid. The pointer is engine-owned; the fake retains the
     * value but does not free it.
     */
    data class AddedBuffer(val bid: Int, val offset: Int, val addr: CPointer<ByteVar>, val len: Int)

    private class Handle(val entries: Int, val bgid: Int) : BufRingHandle

    // --- Call tracking ---

    var setupBufRingCalls: Int = 0
        private set
    var freeBufRingCalls: Int = 0
        private set

    /** Every [addBuffer] invocation in order — bid + ring offset. */
    val addCalls: MutableList<AddCall> = mutableListOf()

    /** Every [addBuffer] invocation in order — full record including payload. */
    val addedBuffers: MutableList<AddedBuffer> = mutableListOf()

    /** The `count` argument of every [advance] invocation in order. */
    val advanceCounts: MutableList<Int> = mutableListOf()

    /**
     * One [advance] invocation paired with the CQE-drain progress at the
     * call site (as supplied by [cqeDrainProgress]). Tests use this to
     * assert ordering of buffer release vs. CQE consumption — e.g.,
     * `advanceEvents == listOf(AdvanceEvent(count = 1, cqesDrainedAt = 3))`
     * means "after 3 CQEs were drained, the engine returned 1 buffer slot
     * to the ring".
     */
    data class AdvanceEvent(val count: Int, val cqesDrainedAt: Int)

    /** Every [advance] invocation in order with CQE-drain progress attached. */
    val advanceEvents: MutableList<AdvanceEvent> = mutableListOf()

    // --- Scripted failures (FIFO) ---

    private val setupResults = ArrayDeque<Int>()
    private val freeResults = ArrayDeque<Int>()

    /** Scripts the next [setupBufRing] call to fail with [errno] (positive). */
    fun scriptSetupFailure(errno: Int) {
        require(errno > 0) { "errno must be positive, got $errno" }
        setupResults.addLast(errno)
    }

    /** Scripts the next [freeBufRing] call to fail with [errno] (positive). */
    fun scriptFreeFailure(errno: Int) {
        require(errno > 0) { "errno must be positive, got $errno" }
        freeResults.addLast(errno)
    }

    override fun setupBufRing(ring: CPointer<io_uring>, entries: Int, bgid: Int): BufRingSetup {
        setupBufRingCalls++
        setupResults.removeFirstOrNull()?.let { return BufRingSetup.Failed(-it) }
        return BufRingSetup.Ok(Handle(entries, bgid))
    }

    override fun addBuffer(handle: BufRingHandle, addr: CPointer<ByteVar>, len: Int, bid: Int, offset: Int) {
        addCalls.add(AddCall(bid, offset))
        addedBuffers.add(AddedBuffer(bid, offset, addr, len))
    }

    /**
     * Returns the most recent (addr, len) pair published for [bid], or
     * `null` if no buffer with that bid has been added. Helper for tests
     * that drive a CQE→buffer→codec scenario and need to recover the
     * payload the engine staged for a given bid.
     */
    fun bufferForBid(bid: Int): Pair<CPointer<ByteVar>, Int>? =
        addedBuffers.lastOrNull { it.bid == bid }?.let { it.addr to it.len }

    override fun advance(handle: BufRingHandle, count: Int) {
        advanceCounts.add(count)
        advanceEvents.add(AdvanceEvent(count, cqeDrainProgress()))
    }

    override fun freeBufRing(ring: CPointer<io_uring>, handle: BufRingHandle, entries: Int, bgid: Int): Int {
        freeBufRingCalls++
        freeResults.removeFirstOrNull()?.let { return -it }
        return 0
    }
}
