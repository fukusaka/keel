@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.pipeline.IoTransport
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.socket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/**
 * Fixture for the [ReadinessIoTransport] seam tests that live in this module,
 * beside the implementation, rather than in the engines.
 *
 * The engines' transport seam fixtures drive the same class through a real
 * `KqueueEventLoop` / `EpollEventLoop`; this one drives it through the
 * [FakeLoop] it shares with the loop tests, with [FakeLoop.armedCallbacks]
 * recording what `registerWriteCallback` would have armed. [setUp] builds
 * it coalescing-off so `flush()` drains synchronously through the funnel's
 * shared exit; tests that need the coalesced tick or a deferred dispatch
 * close it and build their own. The flush paths under test never need
 * readiness delivered, so no thread and no kernel; tests that need a
 * concrete loop's cinterop stay engine-side, per the project's seam /
 * integration split.
 *
 * The syscall fake, the tracking allocator and the disposable fd are fixture
 * state so the tests do not repeat the same construction preamble;
 * [transport] wires the three together.
 */
@OptIn(ExperimentalForeignApi::class)
internal abstract class TransportSeamFixture : AbstractReadinessEventLoopFixture() {

    protected lateinit var eventLoop: FakeLoop
    protected lateinit var fake: FakeNativeSocket
    protected lateinit var tracker: TrackingAllocator
    protected var fd: Int = -1

    @BeforeTest
    fun setUp() {
        eventLoop = FakeLoop(flushCoalescing = false)
        fake = FakeNativeSocket()
        tracker = TrackingAllocator(DefaultAllocator)
        // Disposable real socket fd: the transport's teardown ends in
        // closeFdSafely, and a fabricated number would make that report an
        // EBADF on every test. No real I/O happens; the fake intercepts every
        // byte-level syscall.
        fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed in test setUp" }
    }

    @AfterTest
    fun tearDown() {
        eventLoop.close()
        // EBADF when a test's transport.close() already released it — ignored,
        // like the engine fixtures ignore it.
        if (fd >= 0) close(fd)
    }

    /** The transport under test, wired to the fixture's loop, allocator and syscall fake. */
    protected fun transport(): ReadinessIoTransport = ReadinessIoTransport(fd, eventLoop, tracker, fake)

    /**
     * Replaces the [setUp] loop with one built to the test's shape, closing
     * the old one first — the pair the tests all need in that order, owned
     * here so a copy cannot drop the close and silently skip the fixture
     * loop's teardown.
     */
    protected fun rebuildLoop(
        onLoopThread: Boolean = true,
        runDispatchedInline: Boolean = true,
        flushCoalescing: Boolean = true,
    ) {
        eventLoop.close()
        eventLoop = FakeLoop(onLoopThread, runDispatchedInline, flushCoalescing)
    }

    /**
     * Parks a flush waiter and hands back its outcome: started undispatched
     * so an on-loop register runs inline inside this call, with the result
     * caught so a failed await cannot cancel the test's own scope before
     * its assertions run.
     */
    protected fun CoroutineScope.parkFlushWaiter(transport: ReadinessIoTransport): Deferred<Result<Unit>> =
        async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { transport.awaitPendingFlush() }
        }
}

/** Wall-clock bound for the seam tests' parked waiters and dispatched work; shared budget. */
internal const val FUNNEL_TIMEOUT_MS = 5_000L

/** The transport's water marks; tying the tests to the real thresholds. */
internal const val HIGH_WATER = IoTransport.DEFAULT_HIGH_WATER_MARK
internal const val LOW_WATER = IoTransport.DEFAULT_LOW_WATER_MARK
