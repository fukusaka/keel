package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.logging.LogLevel
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
 * Covers what `bind()` must leave undone: the listener fd stays unarmed until
 * an `accept()` waiter exists to receive the event.
 *
 * The event loop holds the invariant that an armed filter has a handler behind
 * it — [KqueueEventLoop.dispatchReady]'s no-handler branch logs a WARN and
 * issues `EV_DELETE`, calling the state "armed without a corresponding
 * handler". Arming at bind time broke that on the normal path, because
 * `accept()` only registers a continuation once its non-blocking accept returns
 * EAGAIN, so a connection arriving first woke the loop with nothing to hand the
 * event to.
 *
 * The consequence is the second test here: that stale-watch removal is
 * asynchronous, so it can land after a later `bind()` has armed the same
 * recycled fd number, leaving a live listener watched by nobody.
 */
@OptIn(ExperimentalForeignApi::class)
class KqueueBindRegistrationTest {

    @Test
    fun `a connection arriving before accept does not strand the listener filter`() = runBlocking {
        withTimeout(TEST_TIMEOUT) {
            val logger = RecordingLogger(LogLevel.WARN)
            val engine = KqueueEngine(config = IoEngineConfig(loggerFactory = { logger }))
            try {
                val server = engine.bind("127.0.0.1", 0)
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
                    logger.messages.none { STALE_FILTER_MARKER in it },
                    "bind armed the listener filter with no handler behind it: ${logger.messages}",
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
            val engine = KqueueEngine()
            try {
                // Leave an accept() suspended so the loop records state for this
                // fd, then close the server so the number becomes reusable.
                // delay() rather than a blocking sleep: the accept runs on this
                // dispatcher, so blocking the thread would stop it ever reaching
                // its suspend point.
                val first = engine.bind("127.0.0.1", 0)
                val pending = launch { runCatching { first.accept() } }
                delay(SETTLE_MILLIS)
                first.close()
                pending.join()

                // The next listen socket takes the lowest free descriptor, which
                // is the number just released.
                val second = engine.bind("127.0.0.1", 0)
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

    private companion object {
        val TEST_TIMEOUT = 15.seconds

        /** Substring of the no-handler WARN in [KqueueEventLoop.dispatchReady]. */
        const val STALE_FILTER_MARKER = "no handler"

        /** Lets a coroutine on this dispatcher reach its suspend point. */
        const val SETTLE_MILLIS = 300L
    }
}
