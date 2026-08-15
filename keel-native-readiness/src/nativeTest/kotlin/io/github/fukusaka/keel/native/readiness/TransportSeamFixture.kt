@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Runnable
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.socket
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/**
 * Fixture for the [ReadinessIoTransport] seam tests that live in this module,
 * beside the implementation, rather than in the engines.
 *
 * The engines' transport seam fixtures drive the same class through a real
 * `KqueueEventLoop` / `EpollEventLoop`; this one drives it through
 * [TransportSeamLoop], a loop double with no thread and no kernel, because the
 * flush paths under test never need readiness delivered — they need `flush()`
 * to run synchronously and `registerWriteCallback` to be observable. Tests
 * that need a concrete loop's cinterop stay engine-side, per the project's
 * seam / integration split.
 */
@OptIn(ExperimentalForeignApi::class)
internal abstract class TransportSeamFixture {

    protected lateinit var eventLoop: TransportSeamLoop
    protected var fd: Int = -1

    @BeforeTest
    fun setUp() {
        eventLoop = TransportSeamLoop()
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

    /**
     * A loop double for the transport's synchronous paths.
     *
     * [flushCoalescing] is off so `flush()` drains through `performFlush`
     * inline instead of deferring to a tick, [inEventLoop] answers `true` so
     * `close()` and `shutdownOutput()` take their on-loop branch on the test
     * thread, and [submitArmCallback] records what `registerWriteCallback`
     * would have armed instead of issuing a syscall. Everything else a real
     * loop would do — poll, wake, suspend-arm — is inert or refused, because
     * no test here has a readiness event to deliver.
     */
    protected class TransportSeamLoop : AbstractReadinessEventLoop() {

        override val logger: Logger = NoopLoggerFactory.logger("TransportSeam")

        /** Synchronous flush: `flush()` delegates straight to `performFlush`. */
        override val flushCoalescing: Boolean get() = false

        /** What `registerWriteCallback` armed — the observable the walk tests read. */
        val armedCallbacks = mutableListOf<Pair<Int, Interest>>()

        override fun inEventLoop(): Boolean = true

        /** No thread to start; the fixture drives everything directly. */
        override fun start() = Unit

        /** No thread to stop, but the base's gather scratch is still owed back. */
        override fun close() = freeWritevScratch()

        /** No connect path in this double. */
        override suspend fun awaitWriteReady(fd: Int, logger: Logger): Unit =
            error("this double has no connect path")

        /** No kernel to wait on: the loop body and its wakeup are inert here. */
        override fun loopBody() = Unit

        override fun wakeup() = Unit

        override fun removeInterest(fd: Int, interest: Interest) = Unit

        /** No suspend path in this double; the transport tests never arm one. */
        override fun submitArm(
            fd: Int,
            interest: Interest,
            key: Long,
            reg: Registration,
            cont: CancellableContinuation<Unit>,
        ): Unit = error("this double has no suspend path")

        override fun submitArmCallback(fd: Int, interest: Interest, key: Long, listener: FdReadyListener) {
            armedCallbacks.add(fd to interest)
        }

        /** Inline: the test thread is the loop thread here. */
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
    }
}
