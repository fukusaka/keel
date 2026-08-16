@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import io.github.fukusaka.keel.native.readiness.ReadinessIoTransport
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
// one class as they were before the split — the same reason the epoll fixture
// prefixes its own. A bare name here would sit one deleted companion away from
// silently resolving to these instead of failing to compile.

/** Wall-clock bound for anything a seam test waits on. */
internal const val SEAM_TIMEOUT_MS = 5_000L

/** Poll interval while waiting for a waiter to reach its park. */
internal const val SEAM_POLL_MS = 5L

/** Payload size for the half-close ordering tests. */
internal const val SEAM_PAYLOAD_BYTES = 5

/**
 * The fixture shared by the [ReadinessIoTransport] seam tests — the macOS
 * counterpart of `EpollTransportSeamFixture`.
 *
 * Part of the project's two-layer seam + integration testing strategy: the
 * seam tests drive the synchronous code paths (`shutdownOutput`, `flush` /
 * `flushSingle` / `flushGather`) through scripted
 * [io.github.fukusaka.keel.native.posix.FakeNativeSocket] responses,
 * exhausting the errno-branch space without needing real kernel readiness.
 *
 * Split by category across `KqueueTransport*SeamTest` rather than kept in one
 * class, per the project's test-category convention; this holds the setup the
 * split would otherwise have duplicated four times.
 */
@OptIn(ExperimentalForeignApi::class)
internal abstract class KqueueTransportSeamFixture {

    protected val logger = NoopLoggerFactory.logger("KqueueTransportSeam")
    protected lateinit var eventLoop: KqueueEventLoop
    protected var fd: Int = -1

    @BeforeTest
    fun setUp() {
        // Disable the per-tick flush coalescing so `flush()` drains
        // synchronously through the funnel's shared exit — the seam tests exercise
        // errno branches / syscall behaviour and are not driving an EL
        // thread that could drain the deferred runnable. Under the opt-out
        // the semantics reduce to pre-#899 immediate-send.
        eventLoop = KqueueEventLoop(logger, flushCoalescing = false)
        // Disposable real socket fd — needed for `kevent` in WouldBlock
        // branch (`registerWriteCallback`). No real I/O happens; the
        // fake intercepts every byte-level syscall.
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
     * publishes the loop thread's writes to this one —
     * [io.github.fukusaka.keel.native.posix.FakeNativeSocket] is documented
     * single-threaded, so its counters must not be polled while the loop may
     * still be touching them.
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
    protected fun assertStrandedWritesReleased(transport: ReadinessIoTransport, tracker: TrackingAllocator) {
        transport.close()
        assertEquals(0, tracker.outstandingCount, "the stranded writes must be released on close")
    }
}
