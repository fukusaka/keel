@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import kotlinx.cinterop.ExperimentalForeignApi
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
 * [FakeLoop] it shares with the loop tests — coalescing off, so `flush()`
 * drains synchronously through `performFlush`, and with
 * [FakeLoop.armedCallbacks] recording what `registerWriteCallback` would have
 * armed. The flush paths under test never need readiness delivered, so no
 * thread and no kernel; tests that need a concrete loop's cinterop stay
 * engine-side, per the project's seam / integration split.
 *
 * The syscall fake, the tracking allocator and the disposable fd are fixture
 * state so the twelve tests do not repeat the same construction preamble;
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
}
