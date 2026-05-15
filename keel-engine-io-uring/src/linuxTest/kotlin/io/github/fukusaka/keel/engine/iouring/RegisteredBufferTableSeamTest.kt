package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.rawValue
import platform.posix.ENOMEM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for [RegisteredBufferTable] via
 * [FakeIoUringRegisteredBufferOps] injection. Covers the
 * `io_uring_register_buffers` / `unregister_buffers` failure branches —
 * only reachable under real kernel pressure (`ENOMEM` when the kernel
 * cannot pin the pages) — and verifies the pointer→index map handling.
 *
 * Part of the io_uring native API seam effort. The table is driven
 * pre-`start()`, where [IoUringEventLoop.assertInEventLoop] no-ops, so
 * the tests run synchronously on the test thread — no timeout needed.
 */
@OptIn(ExperimentalForeignApi::class)
class RegisteredBufferTableSeamTest {

    private val logger = NoopLoggerFactory.logger("RegisteredBufferTableSeamTest")

    private companion object {
        const val BUF_CAP = 64
    }

    /**
     * Builds a [RegisteredBufferTable] backed by [fake] over [bufferCount]
     * freshly-allocated native buffers, runs [block] with the table and the
     * buffer pointer list, then frees everything.
     */
    private fun withTable(
        fake: FakeIoUringRegisteredBufferOps,
        bufferCount: Int = 3,
        block: (RegisteredBufferTable, List<CPointer<ByteVar>>) -> Unit,
    ) {
        val el = IoUringEventLoop(logger, syscallOps = FakeIoUringSyscallOps())
        val ptrs = List(bufferCount) { nativeHeap.allocArray<ByteVar>(BUF_CAP) }
        val buffers = ptrs.map { it to BUF_CAP }
        try {
            block(RegisteredBufferTable(el, buffers, logger, fake), ptrs)
        } finally {
            ptrs.forEach { nativeHeap.free(it.rawValue) }
            el.close()
        }
    }

    // --- initOnEventLoop ---

    @Test
    fun `initOnEventLoop registers the buffers and activates the table`() {
        val fake = FakeIoUringRegisteredBufferOps()
        withTable(fake, bufferCount = 3) { table, _ ->
            table.initOnEventLoop()
            assertTrue(table.isActive)
            assertEquals(1, fake.registerBuffersCalls)
            assertEquals(3, fake.lastRegisteredCount)
        }
    }

    @Test
    fun `initOnEventLoop with register failure leaves the table inactive and clears the index map`() {
        val fake = FakeIoUringRegisteredBufferOps().apply { scriptRegisterFailure(ENOMEM) }
        withTable(fake, bufferCount = 3) { table, ptrs ->
            table.initOnEventLoop()
            assertFalse(table.isActive, "ENOMEM from register_buffers must leave the table inactive")
            // A failed registration clears the pointer→index map so a stale
            // index is never handed to SEND_ZC_FIXED.
            assertEquals(-1, table.indexOf(ptrs[0]))
        }
    }

    @Test
    fun `initOnEventLoop with empty buffers is a silent no-op`() {
        val fake = FakeIoUringRegisteredBufferOps()
        withTable(fake, bufferCount = 0) { table, _ ->
            table.initOnEventLoop()
            assertFalse(table.isActive)
            assertEquals(0, fake.registerBuffersCalls, "no syscall should fire when there are no buffers")
        }
    }

    @Test
    fun `initOnEventLoop is idempotent`() {
        val fake = FakeIoUringRegisteredBufferOps()
        withTable(fake) { table, _ ->
            table.initOnEventLoop()
            table.initOnEventLoop()
            assertEquals(1, fake.registerBuffersCalls, "second call must be a no-op once registered")
        }
    }

    // --- indexOf ---

    @Test
    fun `indexOf returns the registered index for each pointer`() {
        val fake = FakeIoUringRegisteredBufferOps()
        withTable(fake, bufferCount = 3) { table, ptrs ->
            table.initOnEventLoop()
            assertEquals(0, table.indexOf(ptrs[0]))
            assertEquals(1, table.indexOf(ptrs[1]))
            assertEquals(2, table.indexOf(ptrs[2]))
        }
    }

    @Test
    fun `indexOf returns -1 for an unregistered pointer`() {
        val fake = FakeIoUringRegisteredBufferOps()
        withTable(fake, bufferCount = 2) { table, _ ->
            table.initOnEventLoop()
            val stranger = nativeHeap.allocArray<ByteVar>(BUF_CAP)
            try {
                assertEquals(-1, table.indexOf(stranger))
            } finally {
                nativeHeap.free(stranger.rawValue)
            }
        }
    }

    // --- close ---

    @Test
    fun `close unregisters the buffers and deactivates the table`() {
        val fake = FakeIoUringRegisteredBufferOps()
        withTable(fake) { table, _ ->
            table.initOnEventLoop()
            table.close()
            assertEquals(1, fake.unregisterBuffersCalls)
            assertFalse(table.isActive)
        }
    }

    @Test
    fun `close with unregister failure still deactivates the table`() {
        val fake = FakeIoUringRegisteredBufferOps().apply { scriptUnregisterFailure(ENOMEM) }
        withTable(fake) { table, _ ->
            table.initOnEventLoop()
            table.close()
            assertFalse(table.isActive, "table must deactivate even when unregister_buffers fails")
        }
    }

    @Test
    fun `close before init does not call unregister_buffers`() {
        val fake = FakeIoUringRegisteredBufferOps()
        withTable(fake) { table, _ ->
            table.close()
            assertEquals(0, fake.unregisterBuffersCalls, "nothing was registered — nothing to unregister")
        }
    }
}
