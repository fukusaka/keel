package io.github.fukusaka.keel.engine.iouring

import io_uring.io_uring
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * In-memory [IoUringFileOps] that lets tests script kernel registration
 * outcomes and inspect the fixed-file table state. Single-threaded — only
 * safe to drive from the test thread.
 *
 * Each method consults a FIFO of scripted outcomes (`script*Failure`);
 * when the queue is empty it applies the happy-path default and mutates
 * an in-memory [IntArray] model of the kernel's fixed-file table, so a
 * test can assert which slot holds which fd without a real kernel.
 *
 * The `ring` argument is accepted to satisfy the interface but ignored —
 * the fake keys all state off its own table model.
 */
@OptIn(ExperimentalForeignApi::class)
internal class FakeIoUringFileOps : IoUringFileOps {

    /** A single `updateSlot` invocation, for call-sequence assertions. */
    data class SlotUpdate(val index: Int, val fd: Int)

    // --- In-memory table model ---

    private var table: IntArray? = null

    /** Current fixed-file table size, or `null` if no table is registered. */
    val tableSize: Int? get() = table?.size

    /** Returns the fd at table slot [index], or `null` if no table / out of range. */
    fun slotAt(index: Int): Int? = table?.getOrNull(index)

    // --- Call tracking ---

    var registerEmptyTableCalls: Int = 0
        private set
    var updateSlotCalls: Int = 0
        private set
    var unregisterTableCalls: Int = 0
        private set

    /** Every [updateSlot] invocation in order — index + fd. */
    val slotUpdates: MutableList<SlotUpdate> = mutableListOf()

    // --- Scripted failures (FIFO) ---

    private val registerTableResults = ArrayDeque<Int>()
    private val updateSlotResults = ArrayDeque<Int>()
    private val unregisterTableResults = ArrayDeque<Int>()

    /** Scripts the next [registerEmptyTable] call to fail with [errno] (encoded `-errno`). */
    fun scriptRegisterTableFailure(errno: Int) {
        require(errno > 0) { "errno must be positive, got $errno" }
        registerTableResults.addLast(-errno)
    }

    /** Scripts the next [updateSlot] call to fail with [errno] (encoded `-errno`). */
    fun scriptUpdateSlotFailure(errno: Int) {
        require(errno > 0) { "errno must be positive, got $errno" }
        updateSlotResults.addLast(-errno)
    }

    /** Scripts the next [unregisterTable] call to fail with [errno] (encoded `-errno`). */
    fun scriptUnregisterTableFailure(errno: Int) {
        require(errno > 0) { "errno must be positive, got $errno" }
        unregisterTableResults.addLast(-errno)
    }

    override fun registerEmptyTable(ring: CPointer<io_uring>, count: Int): Int {
        registerEmptyTableCalls++
        registerTableResults.removeFirstOrNull()?.let { return it }
        table = IntArray(count) { -1 }
        return 0
    }

    override fun updateSlot(ring: CPointer<io_uring>, index: Int, fd: Int): Int {
        updateSlotCalls++
        slotUpdates.add(SlotUpdate(index, fd))
        updateSlotResults.removeFirstOrNull()?.let { return it }
        table?.let { if (index in it.indices) it[index] = fd }
        return 1 // number of slots updated
    }

    override fun unregisterTable(ring: CPointer<io_uring>): Int {
        unregisterTableCalls++
        unregisterTableResults.removeFirstOrNull()?.let { return it }
        table = null
        return 0
    }
}
