package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import platform.posix.usleep
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Asserts `bind()` leaves the listener fd unarmed until an `accept()` waiter
 * exists to receive the event.
 *
 * The event loop holds the invariant that an armed filter has a handler behind
 * it: [KqueueEventLoop.dispatchReady]'s no-handler branch logs a WARN and issues
 * `EV_DELETE`, calling the state "armed without a corresponding handler". Arming
 * the listener at bind time breaks that invariant on the normal path, because
 * `accept()` only registers a continuation once its non-blocking accept returns
 * EAGAIN — so a connection arriving first wakes the loop with nothing to hand
 * the event to.
 *
 * That WARN is what this test detects. The race it stands in for is not
 * reproducible on demand: the loop's `EV_DELETE` for the stale filter can land
 * after a later `bind()` has armed the same recycled fd number, silently
 * disarming a live listener so its `accept()` never fires. Rather than chase
 * that timing, this pins the invariant whose violation makes it reachable at
 * all, and the fd is registered through the same funnel every other
 * registration uses.
 */
@OptIn(ExperimentalForeignApi::class)
class KqueueBindRegistrationTest {

    /** Records WARN messages so the stale-filter complaint can be asserted on. */
    private class CapturingLogger(private val sink: MutableList<String>) : Logger {
        override fun isLoggable(level: LogLevel): Boolean = level == LogLevel.WARN

        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            if (level == LogLevel.WARN) sink.add(message.toString())
        }
    }

    @Test
    fun `a connection arriving before accept does not strand the listener filter`() = runBlocking {
        withTimeout(TEST_TIMEOUT) {
            val warnings = mutableListOf<String>()
            val engine = KqueueEngine(config = IoEngineConfig(loggerFactory = { CapturingLogger(warnings) }))
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            // Connect without calling accept() first. The kernel completes the
            // handshake from the backlog, so the listener fd becomes readable
            // while no accept() waiter exists.
            val clientFd = connectRawClient(port)
            // Let the boss loop run a kevent() cycle over the readable listener.
            usleep(BOSS_LOOP_SETTLE_MICROS)

            assertTrue(
                warnings.none { STALE_FILTER_MARKER in it },
                "bind armed the listener filter with no handler behind it: $warnings",
            )

            // The pending connection must still be acceptable — deferring the
            // registration must not lose an event that arrived before it.
            val channel = server.accept()
            channel.close()

            close(clientFd)
            server.close()
            engine.close()
        }
    }

    private companion object {
        val TEST_TIMEOUT = 15.seconds

        /** Substring of the no-handler WARN in [KqueueEventLoop.dispatchReady]. */
        const val STALE_FILTER_MARKER = "no handler"

        /** Long enough for the boss loop to wake, dispatch and log on loopback. */
        const val BOSS_LOOP_SETTLE_MICROS = 300_000u
    }
}
