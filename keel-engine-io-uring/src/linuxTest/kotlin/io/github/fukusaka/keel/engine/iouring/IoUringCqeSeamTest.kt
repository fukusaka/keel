package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.EINTR
import platform.posix.ENOMEM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for [IoUringEventLoop.runIteration]'s CQE-drain
 * dispatch via [FakeIoUringRing] injection. Covers the
 * `io_uring_submit_and_wait` error branches (`EINTR` retry / fatal exit)
 * and the per-`user_data` CQE dispatch — wakeup re-arm, the callback /
 * multishot slot lifecycle, reserved-token discard — none of which a
 * loopback integration test can drive deterministically.
 *
 * Part of the io_uring native API seam effort. `runIteration` issues no
 * thread-affinity assertion, so the tests drive it directly on the test
 * thread with a caller-owned [Cqe] carrier — no EventLoop pthread, no
 * timeout needed.
 *
 * CQEs are scripted manually via [FakeIoUringRing.enqueueCqe]; the fake
 * does not auto-generate CQEs from submitted SQEs (the slot `user_data`
 * is reconstructed in the test from the `submit*` return value plus
 * [IoUringEventLoop.SLOT_BASE]).
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringCqeSeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringCqeSeamTest")

    private fun withEventLoop(ring: FakeIoUringRing, block: (IoUringEventLoop) -> Unit) {
        val el = IoUringEventLoop(
            logger,
            syscallOps = FakeIoUringSyscallOps(),
            ioUringRing = ring,
        )
        try {
            block(el)
        } finally {
            el.close()
            ring.dispose()
        }
    }

    // --- submit_and_wait error branches ---

    @Test
    fun `runIteration returns false on a fatal submit_and_wait error`() {
        val fake = FakeIoUringRing().apply { scriptSubmitAndWait(-ENOMEM) }
        withEventLoop(fake) { el ->
            assertFalse(el.runIteration(Cqe()), "a fatal submit_and_wait errno must stop the loop")
        }
    }

    @Test
    fun `runIteration returns true on EINTR from submit_and_wait`() {
        val fake = FakeIoUringRing().apply { scriptSubmitAndWait(-EINTR) }
        withEventLoop(fake) { el ->
            assertTrue(el.runIteration(Cqe()), "EINTR is a retryable interrupt — the loop continues")
        }
    }

    // --- CQE dispatch ---

    @Test
    fun `runIteration re-arms the wakeup SQE on a WAKEUP_TOKEN CQE`() {
        val fake = FakeIoUringRing()
        withEventLoop(fake) { el ->
            fake.enqueueCqe(userData = IoUringEventLoop.WAKEUP_TOKEN, res = 8)
            assertTrue(el.runIteration(Cqe()))
            // The wakeup CQE drives submitWakeupSqe(), which acquires a fresh SQE.
            assertEquals(1, fake.getSqeCalls, "a WAKEUP_TOKEN CQE must re-arm the wakeup SQE")
        }
    }

    @Test
    fun `runIteration invokes the callback for a slot CQE and releases the slot`() {
        val fake = FakeIoUringRing()
        withEventLoop(fake) { el ->
            var got: Pair<Int, UInt>? = null
            val slot = el.submitCallback(prepare = { }, onCqe = { res, flags -> got = res to flags })
            fake.enqueueCqe(
                userData = slot.toULong() + IoUringEventLoop.SLOT_BASE,
                res = 42,
                flags = 0u,
                hasMore = false,
            )
            assertTrue(el.runIteration(Cqe()))
            assertEquals(42 to 0u, got, "the slot callback must receive the CQE's res / flags")
            // hasMore = false released the slot — the next submit reuses it.
            val reused = el.submitCallback(prepare = { }, onCqe = { _, _ -> })
            assertEquals(slot, reused, "a single-shot CQE must release the slot for reuse")
        }
    }

    @Test
    fun `runIteration keeps the slot for a multishot CQE with F_MORE then releases on the final CQE`() {
        val fake = FakeIoUringRing()
        withEventLoop(fake) { el ->
            val results = mutableListOf<Int>()
            val slot = el.submitMultishot(prepare = { }, onCqe = { res, _ -> results.add(res) })
            val userData = slot.toULong() + IoUringEventLoop.SLOT_BASE
            // First CQE keeps the slot (F_MORE), second releases it.
            fake.enqueueCqe(userData = userData, res = 1, hasMore = true)
            fake.enqueueCqe(userData = userData, res = 2, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(listOf(1, 2), results, "both multishot CQEs must reach the callback")
            val reused = el.submitMultishot(prepare = { }, onCqe = { _, _ -> })
            assertEquals(slot, reused, "the final CQE (F_MORE cleared) must release the slot")
        }
    }

    @Test
    fun `runIteration discards a CANCEL_TOKEN CQE`() {
        val fake = FakeIoUringRing()
        withEventLoop(fake) { el ->
            fake.enqueueCqe(userData = IoUringEventLoop.CANCEL_TOKEN, res = -125)
            assertTrue(el.runIteration(Cqe()), "a cancel-completion CQE is discarded without error")
        }
    }

    @Test
    fun `runIteration skips a reserved-range userData`() {
        val fake = FakeIoUringRing()
        withEventLoop(fake) { el ->
            // user_data 0 is reserved (below SLOT_BASE and not a token) — a
            // safety skip, never indexed into the slot pool.
            fake.enqueueCqe(userData = 0u, res = 0)
            assertTrue(el.runIteration(Cqe()), "a reserved-range user_data is skipped safely")
        }
    }

    // --- callback exception isolation ---

    @Test
    fun `runIteration keeps the loop alive when a slot callback throws`() {
        val fake = FakeIoUringRing()
        withEventLoop(fake) { el ->
            val slot = el.submitCallback(prepare = { }, onCqe = { _, _ -> error("callback boom") })
            fake.enqueueCqe(userData = slot.toULong() + IoUringEventLoop.SLOT_BASE, res = 1, hasMore = false)
            // A throwing callback must be caught — the loop continues and the
            // slot is still released (a crash here would strand the EL thread).
            assertTrue(el.runIteration(Cqe()), "a throwing CQE callback must not stop the loop")
            val reused = el.submitCallback(prepare = { }, onCqe = { _, _ -> })
            assertEquals(slot, reused, "the slot must be released even when the callback threw")
        }
    }

    // --- SEND_ZC two-CQE completion ---

    @Test
    fun `runIteration completes a SEND_ZC operation across both CQEs`() {
        val fake = FakeIoUringRing()
        withEventLoop(fake) { el ->
            var completed: Int? = null
            memScoped {
                val buf = alloc<ByteVar>()
                el.submitSendZcCallback(fd = 7, buf = buf.ptr, len = 8u, flags = 0) { completed = it }
            }
            val userData = fake.lastSqeUserData()
            // First CQE carries the send result + F_MORE (notification follows);
            // second CQE is the buffer-release notification.
            fake.enqueueCqe(userData = userData, res = 64, hasMore = true)
            fake.enqueueCqe(userData = userData, res = 0, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(64, completed, "onComplete must fire once with the send result after both CQEs")
        }
    }

    @Test
    fun `runIteration completes a SEND_ZC operation when the notification CQE is omitted`() {
        val fake = FakeIoUringRing()
        withEventLoop(fake) { el ->
            var completed: Int? = null
            memScoped {
                val buf = alloc<ByteVar>()
                el.submitSendZcCallback(fd = 7, buf = buf.ptr, len = 8u, flags = 0) { completed = it }
            }
            // A single CQE without F_MORE: the kernel produced no separate
            // notification, so the operation completes on the first CQE.
            fake.enqueueCqe(userData = fake.lastSqeUserData(), res = 64, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(64, completed, "onComplete must fire on the first CQE when F_MORE is clear")
        }
    }

    // --- wakeup SQE deferred retry ---

    @Test
    fun `runIteration retries a deferred wakeup SQE once the SQ ring drains`() {
        val fake = FakeIoUringRing().apply { scriptSqRingFull() }
        withEventLoop(fake) { el ->
            // Iteration 1: a WAKEUP_TOKEN CQE drives submitWakeupSqe(), but the
            // SQ ring is full (scripted) — submission is deferred.
            fake.enqueueCqe(userData = IoUringEventLoop.WAKEUP_TOKEN, res = 8)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(1, fake.getSqeCalls, "iteration 1: the deferred wakeup SQE consumed one getSqe")
            // Iteration 2: the SQ ring has drained — the deferred submission
            // is retried at the top of the loop.
            assertTrue(el.runIteration(Cqe()))
            assertEquals(2, fake.getSqeCalls, "iteration 2: the deferred wakeup SQE is retried")
        }
    }
}
