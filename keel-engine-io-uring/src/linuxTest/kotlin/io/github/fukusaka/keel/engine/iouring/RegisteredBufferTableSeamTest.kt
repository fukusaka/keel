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

    // --- partial-failure recovery (audit-2 follow-up) ---

    @Test
    fun `close after a failed init is a silent no-op`() {
        // The warmup orchestration in IoUringEventLoopGroup wires close() via
        // onExitHook, which fires unconditionally on EL exit. A failed init
        // must not cause close() to issue a spurious unregister_buffers call —
        // the kernel never accepted the registration, so there is nothing to
        // unregister. A spurious call would error (`-EINVAL` typically) and
        // produce a noisy warn-log on every shutdown.
        val fake = FakeIoUringRegisteredBufferOps().apply { scriptRegisterFailure(ENOMEM) }
        withTable(fake, bufferCount = 3) { table, _ ->
            table.initOnEventLoop()
            assertFalse(table.isActive, "init must have failed")
            table.close()
            assertEquals(
                0,
                fake.unregisterBuffersCalls,
                "register_buffers never succeeded — close must NOT call unregister_buffers",
            )
        }
    }

    @Test
    fun `initOnEventLoop after a failed init can be retried and succeeds`() {
        // Defensive: a transient kernel pressure (-ENOMEM) on first init must
        // not permanently strand the table. The next initOnEventLoop call has
        // to retry — `isActive` is still false, so the early-return gate does
        // not fire. The audit context was the warmup orchestration; this also
        // covers a manual retry path if a caller chooses to re-attempt.
        val fake = FakeIoUringRegisteredBufferOps().apply { scriptRegisterFailure(ENOMEM) }
        withTable(fake, bufferCount = 3) { table, ptrs ->
            table.initOnEventLoop()
            assertFalse(table.isActive, "first init failed")
            assertEquals(1, fake.registerBuffersCalls)

            // Second attempt — the failure queue is empty so the fake returns
            // the happy-path default (0).
            table.initOnEventLoop()
            assertTrue(table.isActive, "second init must succeed when the kernel recovers")
            assertEquals(2, fake.registerBuffersCalls, "retry must call register_buffers a second time")

            // The pointer→index map was cleared on the first failure but the
            // second successful init does NOT rebuild it (constructor-time
            // population only). This is the current contract — pin it so a
            // future change that touches retry behaviour is forced to surface
            // the rebuild question explicitly.
            assertEquals(-1, table.indexOf(ptrs[0]), "ptrToIndex was cleared on first failure and is not rebuilt")
        }
    }

    @Test
    fun `indexOf returns -1 for every registered pointer after a failed init`() {
        // ptrToIndex.clear() must wipe ALL entries, not just one. A stale
        // partial-clear would hand a kernel-rejected index to SEND_ZC_FIXED
        // and the kernel would reject the submit (EFAULT / EINVAL).
        val fake = FakeIoUringRegisteredBufferOps().apply { scriptRegisterFailure(ENOMEM) }
        withTable(fake, bufferCount = 3) { table, ptrs ->
            table.initOnEventLoop()
            assertFalse(table.isActive)
            for ((i, ptr) in ptrs.withIndex()) {
                assertEquals(-1, table.indexOf(ptr), "bid $i must be unresolvable after failed init")
            }
        }
    }
}
