@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import io.github.fukusaka.keel.native.readiness.PosixIoTransport
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.socket
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals

// `SEAM_`-prefixed because these are package-visible, not companion members of
// one class as they were before the split. Two sibling test files declare their
// own `PAYLOAD_BYTES` (1024) and `POLL_MS`, and a bare name here would sit one
// deleted companion away from resolving to these instead -- silently, since it
// still compiles and a 1024-byte payload test would just start using 5.

/** Wall-clock bound for anything a seam test waits on. */
internal const val SEAM_TIMEOUT_MS = 5_000L

/** Poll interval while waiting for a waiter to reach its park. */
internal const val SEAM_POLL_MS = 5L

/** Payload size for the half-close ordering tests. */
internal const val SEAM_PAYLOAD_BYTES = 5

/**
 * The fixture shared by the [PosixIoTransport] seam tests.
 *
 * Part of the project's two-layer seam + integration testing strategy: the
 * seam tests exhaust the errno-branch space of the synchronous code paths
 * (`shutdownOutput`, `flush` / `flushSingle` / `flushGather`) against a fake
 * [io.github.fukusaka.keel.native.posix.NativeSocket], without relying on
 * kernel readiness. Paths that need real readiness events (`onReadable`)
 * stay with the integration tests.
 *
 * Split by category across `EpollTransport*SeamTest` rather than kept in one
 * class, per the project's test-category convention; this holds the setup the
 * split would otherwise have duplicated four times.
 */
@OptIn(ExperimentalForeignApi::class)
internal abstract class EpollTransportSeamFixture {

    protected val logger = NoopLoggerFactory.logger("EpollTransportSeam")
    protected lateinit var eventLoop: EpollEventLoop
    protected var fd: Int = -1

    @BeforeTest
    fun setUp() {
        // Disable the per-tick flush coalescing so `flush()` delegates
        // synchronously to `performFlush()` — the seam tests exercise
        // errno branches / syscall behaviour and are not driving an EL
        // thread that could drain the deferred runnable. Under the opt-out
        // the semantics reduce to pre-#900 immediate-send.
        eventLoop = EpollEventLoop(logger, flushCoalescing = false)
        // Disposable real socket fd — needed for epoll_ctl in WouldBlock
        // branch (`registerWriteCallback`). No real I/O happens; the fake
        // intercepts every byte-level syscall.
        fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed in test setUp" }
    }

    @AfterTest
    fun tearDown() {
        // Stop the loop first: the transport registered [fd] with it, so closing
        // the descriptor while the loop thread is still polling or arming it is
        // the recycled-fd hazard the engine funnels exist to avoid. Harmless
        // until these tests started the loop; now it is not.
        eventLoop.close()
        if (fd >= 0) close(fd)
    }

    /**
     * Returns once the loop has run everything dispatched so far.
     *
     * A marker task goes through the same FIFO queue, so when it completes the
     * work queued before it has already run. Awaiting the deferred also
     * publishes the loop thread's writes to this one — [io.github.fukusaka.keel.native.posix.FakeNativeSocket]
     * is documented single-threaded, so its counters must not be polled while
     * the loop may still be touching them.
     */
    protected suspend fun awaitLoopDrained() {
        val marker = CompletableDeferred<Unit>()
        eventLoop.dispatch(EmptyCoroutineContext, Runnable { marker.complete(Unit) })
        withTimeout(SEAM_TIMEOUT_MS) { marker.await() }
    }

    /**
     * A test that leaves writes stranded still owns them: `close()` is what
     * releases the queue, and without it the pooled buffers outlive the test.
     * The sibling flush tests assert this the same way.
     */
    protected fun assertStrandedWritesReleased(transport: PosixIoTransport, tracker: TrackingAllocator) {
        transport.close()
        assertEquals(0, tracker.outstandingCount, "the stranded writes must be released on close")
    }
}
