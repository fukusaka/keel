package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.InetSocketAddress

import io.github.fukusaka.keel.core.IoEngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Regression tests for the `ClassCastException: CompletedContinuation cannot be
 * cast to DispatchedContinuation` that fired on `Dispatchers.Default` workers
 * during NIO engine shutdown.
 *
 * Root cause was that `NioEventLoop.processSelectedKeys` used to pick between
 * `is Runnable -> attachment.run()` and `is CancellableContinuation<*> ->
 * (...).resume(Unit)`. `CancellableContinuationImpl` transitively implements
 * `Runnable` (via `DispatchedTask -> scheduling.Task`), so the `Runnable`
 * branch always won and the continuation's own state never transitioned to
 * `CompletedContinuation`, leaving it installed as a stale child handler of
 * the parent Job. Shutdown then fired `cont.cancel()` which dispatched a
 * second `resumeWith` to the user state machine where `releaseIntercepted`
 * hit the `CompletedContinuation` sentinel already set by the first completion.
 *
 * Fixed by attaching a plain `Runnable { cont.resume(Unit) }` to the selector
 * (via `setInterestCallback`) so attachments are never
 * `CancellableContinuationImpl` instances. See [NioServer] KDoc.
 */
class NioShutdownRaceTest {

    private val testTimeout = 10.seconds

    /**
     * Original reproducer pattern (verified 100% reproducible against the
     * pre-fix code in the investigation branch):
     *
     *  1. Bind a server.
     *  2. Start an accept loop.
     *  3. Connect one client so the 1st accept completes.
     *  4. Let the 2nd accept suspend (no more clients).
     *  5. Cancel the accept job.
     *
     * Pre-fix this produced `CoroutinesInternalError` wrapping
     * `ClassCastException` on a Dispatchers.Default worker.
     */
    @Test
    fun `accept cancel after one successful connection does not throw fatal exception`() {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e) }
        try {
            runBlocking {
                withTimeout(testTimeout) {
                    val engine = NioEngine(IoEngineConfig())
                    val server = engine.bind("127.0.0.1", 0)
                    val port = (server.localAddress as InetSocketAddress).port
                    // Dispatchers.Default is intentional: the original race
                    // surfaced as a CoroutinesInternalError on a
                    // DefaultDispatcher-worker thread; reproducing it requires
                    // the same dispatcher.

                    @Suppress("InjectDispatcher")
                    val job = CoroutineScope(Dispatchers.Default).launch {
                        try {
                            while (isActive) {
                                val ch = server.accept()
                                ch.close()
                            }
                        } catch (_: kotlinx.coroutines.CancellationException) {
                            // expected on cancel
                        }
                    }
                    // 1st accept completes via a real connection
                    val sock = Socket(InetAddress.getLoopbackAddress(), port)
                    delay(100)
                    sock.close()
                    // 2nd accept is now suspended without a client
                    delay(200)
                    job.cancelAndJoin()
                    server.close()
                    engine.close()
                    // Give Dispatchers.Default workers time to surface any
                    // CoroutinesInternalError from stale-continuation runs.
                    delay(300)
                }
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        val fatal = uncaught.filter {
            it is ClassCastException ||
                it::class.qualifiedName == "kotlinx.coroutines.CoroutinesInternalError"
        }
        assertTrue(
            fatal.isEmpty(),
            "expected no fatal coroutine exceptions, got: " +
                fatal.joinToString { "${it::class.qualifiedName}: ${it.message}" },
        )
    }

    /**
     * Symmetric coverage for `NioEngine.connect`: on scope cancellation the
     * connect cleanup must not leak a fatal coroutine exception regardless of
     * whether OP_CONNECT actually suspended.
     *
     * Caveat: a single loopback connect against a bound `ServerSocket` usually
     * slots into the kernel's SYN backlog before the user-space `accept()` is
     * called, so the non-blocking `socketChannel.connect()` can return `true`
     * immediately — the race path this PR fixed (continuation stored as
     * SelectionKey attachment during OP_CONNECT wait) may not be hit on every
     * run. The assertion is therefore "no fatal exception emerges regardless
     * of which path runs", not "OP_CONNECT wait always happens". A
     * deterministic SYN-backlog-saturation fixture is out of scope.
     */
    @Test
    fun `connect cancel during OP_CONNECT wait does not throw fatal exception`() {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e) }
        try {
            runBlocking {
                withTimeout(testTimeout) {
                    val engine = NioEngine(IoEngineConfig())
                    // Bound but never accept. One pending connection slots into
                    // the kernel SYN backlog and the non-blocking connect can
                    // complete immediately, so OP_CONNECT wait is not
                    // guaranteed — see the KDoc for the test-scope caveat.
                    val ss = java.net.ServerSocket(0, 1)
                    val port = ss.localPort
                    // Dispatchers.Default is intentional: the original race
                    // surfaced as a CoroutinesInternalError on a
                    // DefaultDispatcher-worker thread; reproducing it requires
                    // the same dispatcher.

                    @Suppress("InjectDispatcher")
                    val job = CoroutineScope(Dispatchers.Default).launch {
                        try {
                            engine.connect("127.0.0.1", port)
                        } catch (_: Throwable) {
                            // Any failure mode is acceptable here; what we are
                            // asserting is the absence of *fatal* coroutine
                            // machinery errors.
                        }
                    }
                    delay(100)
                    job.cancelAndJoin()
                    ss.close()
                    engine.close()
                    delay(300)
                }
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        val fatal = uncaught.filter {
            it is ClassCastException ||
                it::class.qualifiedName == "kotlinx.coroutines.CoroutinesInternalError"
        }
        assertTrue(
            fatal.isEmpty(),
            "expected no fatal coroutine exceptions, got: " +
                fatal.joinToString { "${it::class.qualifiedName}: ${it.message}" },
        )
    }
}
