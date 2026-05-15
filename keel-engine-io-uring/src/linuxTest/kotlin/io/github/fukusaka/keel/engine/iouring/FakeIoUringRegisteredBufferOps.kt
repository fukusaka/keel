package io.github.fukusaka.keel.engine.iouring

import io_uring.io_uring
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * In-memory [IoUringRegisteredBufferOps] that lets tests script kernel
 * registration outcomes and inspect the call sequence. Single-threaded —
 * only safe to drive from the test thread.
 *
 * `registerBuffers` / `unregisterBuffers` consult a FIFO of scripted
 * failures; when the queue is empty they apply the happy-path default
 * (`0`). The native `ring` argument is accepted to satisfy the interface
 * but ignored.
 */
@OptIn(ExperimentalForeignApi::class)
internal class FakeIoUringRegisteredBufferOps : IoUringRegisteredBufferOps {

    // --- Call tracking ---

    var registerBuffersCalls: Int = 0
        private set
    var unregisterBuffersCalls: Int = 0
        private set

    /** Buffer count passed to the most recent [registerBuffers] call, or `-1` if never called. */
    var lastRegisteredCount: Int = -1
        private set

    // --- Scripted failures (FIFO) ---

    private val registerResults = ArrayDeque<Int>()
    private val unregisterResults = ArrayDeque<Int>()

    /** Scripts the next [registerBuffers] call to fail with [errno] (encoded `-errno`). */
    fun scriptRegisterFailure(errno: Int) {
        require(errno > 0) { "errno must be positive, got $errno" }
        registerResults.addLast(-errno)
    }

    /** Scripts the next [unregisterBuffers] call to fail with [errno] (encoded `-errno`). */
    fun scriptUnregisterFailure(errno: Int) {
        require(errno > 0) { "errno must be positive, got $errno" }
        unregisterResults.addLast(-errno)
    }

    override fun registerBuffers(
        ring: CPointer<io_uring>,
        buffers: List<Pair<CPointer<ByteVar>, Int>>,
    ): Int {
        registerBuffersCalls++
        lastRegisteredCount = buffers.size
        return registerResults.removeFirstOrNull() ?: 0
    }

    override fun unregisterBuffers(ring: CPointer<io_uring>): Int {
        unregisterBuffersCalls++
        return unregisterResults.removeFirstOrNull() ?: 0
    }
}
