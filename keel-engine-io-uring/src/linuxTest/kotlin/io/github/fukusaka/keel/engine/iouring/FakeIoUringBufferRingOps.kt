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
internal class FakeIoUringBufferRingOps : IoUringBufferRingOps {

    /** A single `addBuffer` invocation, for publish-path assertions. */
    data class AddCall(val bid: Int, val offset: Int)

    private class Handle(val entries: Int, val bgid: Int) : BufRingHandle

    // --- Call tracking ---

    var setupBufRingCalls: Int = 0
        private set
    var freeBufRingCalls: Int = 0
        private set

    /** Every [addBuffer] invocation in order — bid + ring offset. */
    val addCalls: MutableList<AddCall> = mutableListOf()

    /** The `count` argument of every [advance] invocation in order. */
    val advanceCounts: MutableList<Int> = mutableListOf()

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
    }

    override fun advance(handle: BufRingHandle, count: Int) {
        advanceCounts.add(count)
    }

    override fun freeBufRing(ring: CPointer<io_uring>, handle: BufRingHandle, entries: Int, bgid: Int): Int {
        freeBufRingCalls++
        freeResults.removeFirstOrNull()?.let { return -it }
        return 0
    }
}
