@file:OptIn(InternalPosixEventLoopApi::class)

package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.native.posix.InternalPosixEventLoopApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers what `bind()` must leave undone: the listener fd stays unregistered
 * until an `accept()` waiter exists to receive the event.
 *
 * The event loop holds the invariant that a registered interest has a handler
 * behind it — `AbstractPosixReadinessEventLoop.dispatchReady`'s no-handler branch logs a WARN
 * and removes the interest, calling the state stale. Registering at bind time
 * broke that on the normal path, because `accept()` only registers a
 * continuation once its non-blocking accept returns EAGAIN, so a connection
 * arriving first woke the loop with nothing to hand the event to.
 *
 * The consequence is the second test here: that stale-interest removal is
 * asynchronous, so it can land after a later `bind()` has registered the same
 * recycled fd number, leaving a live listener watched by nobody. On this engine
 * the same fd number is dangerous for a second reason — the loop's own interest
 * bookkeeping, see `EpollStreamServer.close`.
 */
@OptIn(ExperimentalForeignApi::class)
class EpollBindRegistrationTest {

    @Test
    fun `a connection arriving before accept does not strand the listener interest`() = runBlocking {
        withTimeout(TEST_TIMEOUT) {
            val logger = RecordingLogger(LogLevel.WARN)
            val engine = EpollEngine(config = IoEngineConfig(loggerFactory = { logger }))
            try {
                val server = engine.bind(LOOPBACK_HOST, 0)
                val port = (server.localAddress as InetSocketAddress).port

                // Connect without calling accept() first. The kernel completes
                // the handshake from the backlog, so the listener fd becomes
                // readable while no accept() waiter exists.
                val clientFd = connectRawClient(port)

                // Accept before asserting: it only returns once the loop has
                // processed the listener, which is what makes the absence of a
                // WARN below evidence rather than a race the loop had not yet
                // reached. It also shows the deferred registration does not
                // lose a connection that arrived before it.
                val channel = server.accept()
                channel.close()

                assertTrue(
                    logger.messages.none { STALE_INTEREST_MARKER in it },
                    "bind registered the listener with no handler behind it: ${logger.messages}",
                )

                close(clientFd)
                server.close()
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun `a listener bound onto a recycled fd number is still watched`() = runBlocking {
        withTimeout(TEST_TIMEOUT) {
            val engine = EpollEngine()
            try {
                // Leave an accept() suspended so the loop records an interest
                // for this fd, then close the server so the number becomes
                // reusable. delay() rather than a blocking sleep: the accept
                // runs on this dispatcher, so blocking the thread would stop it
                // ever reaching its suspend point.
                val first = engine.bind(LOOPBACK_HOST, 0)
                val firstPort = (first.localAddress as InetSocketAddress).port
                val pending = launch { runCatching { first.accept() } }
                delay(SETTLE_MILLIS)
                first.close()
                pending.join()
                // join() only means the accept coroutine unwound; close()
                // releases the fd asynchronously on the boss loop. Without
                // waiting for the release the next bind can get a different
                // descriptor and this stops testing fd reuse at all — while
                // still passing.
                awaitPortReleased(engine, firstPort)

                // The next listen socket takes the lowest free descriptor, which
                // is the number just released.
                val second = engine.bind(LOOPBACK_HOST, 0)
                val port = (second.localAddress as InetSocketAddress).port

                // Suspend the accept before connecting, so it has to go through
                // the register path instead of taking a connection already
                // waiting in the backlog.
                val accepted = async { second.accept() }
                delay(SETTLE_MILLIS)
                val clientFd = connectRawClient(port)
                val channel = accepted.await()
                channel.close()

                close(clientFd)
                second.close()
            } finally {
                engine.close()
            }
        }
    }

    /**
     * Waits until [port] can be bound again, i.e. until the asynchronous
     * `close()` has actually released the listener's descriptor.
     *
     * The fd-reuse case below depends on the number being free before the next
     * bind; without this the test still passes but silently stops covering
     * reuse.
     */
    private suspend fun awaitPortReleased(engine: EpollEngine, port: Int) {
        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        while (mark.elapsedNow().inWholeMilliseconds < PORT_RELEASE_BUDGET_MS) {
            val probe = runCatching { engine.bind("127.0.0.1", port) }.getOrNull()
            if (probe != null) {
                probe.close()
                return
            }
            delay(PORT_RELEASE_POLL_MILLIS)
        }
        throw AssertionError("port $port still bound ${PORT_RELEASE_BUDGET_MS}ms after close()")
    }

    private companion object {
        val TEST_TIMEOUT = 15.seconds

        /** Substring of the no-handler WARN in `AbstractPosixReadinessEventLoop.dispatchReady`. */
        const val STALE_INTEREST_MARKER = "no handler"

        const val PORT_RELEASE_BUDGET_MS = 2_000L
        const val PORT_RELEASE_POLL_MILLIS = 20L

        /** Lets a coroutine on this dispatcher reach its suspend point. */
        const val SETTLE_MILLIS = 300L
    }
}
