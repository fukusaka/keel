package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.EBADF
import platform.posix.EINVAL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for [FixedFileRegistry]'s kernel registration
 * error branches via [FakeIoUringFileOps] injection. Covers the
 * `io_uring_register_files` / `_files_update` / `unregister_files`
 * failure paths that are only reachable under real kernel pressure
 * (`EINVAL` on an unsupported kernel, `EBADF` on a stale fd) and
 * therefore not exercised by integration tests.
 *
 * Part of the io_uring native API seam effort. The registry is driven
 * pre-`start()`, where [IoUringEventLoop.assertInEventLoop] no-ops
 * (`eventLoopThread == null`, which this engine's assert still permits —
 * its register-class init legitimately runs before the loop), so the tests run synchronously on the
 * test thread without spawning the EventLoop pthread — no timeout is
 * needed (no async / dispatch / I/O).
 */
@OptIn(ExperimentalForeignApi::class)
class FixedFileRegistrySeamTest {

    private val logger = NoopLoggerFactory.logger("FixedFileRegistrySeamTest")

    /**
     * Builds a [FixedFileRegistry] backed by [fileOps] and a pre-`start()`
     * [IoUringEventLoop], runs [block], then tears the EventLoop down.
     */
    private fun withRegistry(
        fileOps: FakeIoUringFileOps,
        maxFiles: Int = 8,
        block: (FixedFileRegistry) -> Unit,
    ) {
        val el = IoUringEventLoop(logger, syscallOps = FakeIoUringSyscallOps())
        try {
            block(FixedFileRegistry(el, logger, maxFiles, fileOps))
        } finally {
            el.close()
        }
    }

    // --- initOnEventLoop ---

    @Test
    fun `initOnEventLoop registers an empty table and activates the registry`() {
        val fake = FakeIoUringFileOps()
        withRegistry(fake, maxFiles = 8) { registry ->
            registry.initOnEventLoop()
            assertTrue(registry.isActive, "registry should be active after successful registration")
            assertEquals(1, fake.registerEmptyTableCalls)
            assertEquals(8, fake.tableSize, "table should hold maxFiles slots")
        }
    }

    @Test
    fun `initOnEventLoop with register failure leaves the registry inactive`() {
        val fake = FakeIoUringFileOps().apply { scriptRegisterTableFailure(EINVAL) }
        withRegistry(fake) { registry ->
            registry.initOnEventLoop()
            assertFalse(registry.isActive, "EINVAL from register_files must leave the registry inactive")
            // A failed registration must not leave a table model behind.
            assertNull(fake.tableSize)
        }
    }

    @Test
    fun `initOnEventLoop is idempotent`() {
        val fake = FakeIoUringFileOps()
        withRegistry(fake) { registry ->
            registry.initOnEventLoop()
            registry.initOnEventLoop()
            assertEquals(1, fake.registerEmptyTableCalls, "second call must be a no-op once registered")
        }
    }

    // --- register ---

    @Test
    fun `register on an inactive registry returns -1`() {
        val fake = FakeIoUringFileOps()
        withRegistry(fake) { registry ->
            // initOnEventLoop not called — registry inactive.
            assertEquals(-1, registry.register(fd = 42))
            assertEquals(0, fake.updateSlotCalls, "no syscall should be issued while inactive")
        }
    }

    @Test
    fun `register updates a slot and returns its index`() {
        val fake = FakeIoUringFileOps()
        withRegistry(fake) { registry ->
            registry.initOnEventLoop()
            val index = registry.register(fd = 42)
            assertEquals(0, index, "first registration takes slot 0")
            assertEquals(listOf(FakeIoUringFileOps.SlotUpdate(0, 42)), fake.slotUpdates)
            assertEquals(42, fake.slotAt(0))
        }
    }

    @Test
    fun `register with updateSlot failure returns -1 and releases the slot`() {
        val fake = FakeIoUringFileOps().apply { scriptUpdateSlotFailure(EBADF) }
        withRegistry(fake) { registry ->
            registry.initOnEventLoop()
            assertEquals(-1, registry.register(fd = 42), "EBADF from files_update must surface as -1")
            // The slot acquired for the failed update must be returned to the
            // bitmap — the next registration reuses index 0.
            assertEquals(0, registry.register(fd = 43), "released slot 0 must be reusable")
        }
    }

    @Test
    fun `register returns -1 when the pool is full`() {
        val fake = FakeIoUringFileOps()
        withRegistry(fake, maxFiles = 2) { registry ->
            registry.initOnEventLoop()
            assertEquals(0, registry.register(fd = 10))
            assertEquals(1, registry.register(fd = 11))
            assertEquals(-1, registry.register(fd = 12), "third registration exceeds maxFiles=2")
        }
    }

    // --- unregister ---

    @Test
    fun `unregister clears the slot and frees it for reuse`() {
        val fake = FakeIoUringFileOps()
        withRegistry(fake) { registry ->
            registry.initOnEventLoop()
            val index = registry.register(fd = 42)
            registry.unregister(index)
            assertEquals(-1, fake.slotAt(index), "unregister must write -1 into the slot")
            assertEquals(FakeIoUringFileOps.SlotUpdate(index, -1), fake.slotUpdates.last())
            assertEquals(index, registry.register(fd = 43), "freed slot must be reusable")
        }
    }

    @Test
    fun `unregister with updateSlot failure still releases the slot`() {
        val fake = FakeIoUringFileOps()
        withRegistry(fake) { registry ->
            registry.initOnEventLoop()
            val index = registry.register(fd = 42)
            fake.scriptUpdateSlotFailure(EBADF) // the clearing update fails
            registry.unregister(index)
            // Even when the kernel update fails, the userspace slot must be
            // freed — otherwise the index leaks.
            assertEquals(index, registry.register(fd = 43), "slot must be freed despite update failure")
        }
    }

    // --- claim ---

    @Test
    fun `claim marks a kernel-allocated index used so register skips it`() {
        val fake = FakeIoUringFileOps()
        withRegistry(fake, maxFiles = 4) { registry ->
            registry.initOnEventLoop()
            assertTrue(registry.claim(index = 2), "claiming a free slot succeeds")
            // claim issues no files_update syscall — the kernel already placed the fd.
            assertEquals(0, fake.updateSlotCalls)
            val handed = listOf(registry.register(1), registry.register(2), registry.register(3))
            assertFalse(handed.contains(2), "register must never hand out the claimed index 2: $handed")
        }
    }

    @Test
    fun `claim on an already-used index returns false`() {
        val fake = FakeIoUringFileOps()
        withRegistry(fake) { registry ->
            registry.initOnEventLoop()
            val index = registry.register(fd = 42)
            assertFalse(registry.claim(index), "claiming an in-use slot is a bookkeeping error")
        }
    }

    @Test
    fun `claim on an inactive registry returns false`() {
        val fake = FakeIoUringFileOps()
        withRegistry(fake) { registry ->
            assertFalse(registry.claim(index = 0), "claim must fail while the registry is inactive")
        }
    }

    @Test
    fun `claim on an out-of-range index returns false`() {
        val fake = FakeIoUringFileOps()
        withRegistry(fake, maxFiles = 4) { registry ->
            registry.initOnEventLoop()
            // The kernel should never direct-allocate an index beyond the
            // registered table size; the bounds guard rejects it rather than
            // indexing past the free-slot bitmap.
            assertFalse(registry.claim(index = 4), "index == maxFiles is out of range")
            assertFalse(registry.claim(index = 99), "index well past maxFiles is out of range")
        }
    }

    // --- close ---

    @Test
    fun `close unregisters the table and deactivates the registry`() {
        val fake = FakeIoUringFileOps()
        withRegistry(fake) { registry ->
            registry.initOnEventLoop()
            registry.close()
            assertEquals(1, fake.unregisterTableCalls)
            assertFalse(registry.isActive)
        }
    }

    @Test
    fun `close with unregister failure still deactivates the registry`() {
        val fake = FakeIoUringFileOps().apply { scriptUnregisterTableFailure(EINVAL) }
        withRegistry(fake) { registry ->
            registry.initOnEventLoop()
            registry.close()
            assertFalse(registry.isActive, "registry must deactivate even when unregister_files fails")
        }
    }
}
