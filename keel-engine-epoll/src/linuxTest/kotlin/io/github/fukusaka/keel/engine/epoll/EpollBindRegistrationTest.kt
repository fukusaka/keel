package io.github.fukusaka.keel.engine.epoll

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
 * The event loop holds the invariant that a registered interest has a handler
 * behind it: [EpollEventLoop.dispatchReady]'s no-handler branch logs a WARN and
 * removes the interest, calling the state stale. Registering the listener at
 * bind time breaks that invariant on the normal path, because `accept()` only
 * registers a continuation once its non-blocking accept returns EAGAIN — so a
 * connection arriving first wakes the loop with nothing to hand the event to.
 *
 * That WARN is what this test detects. The race it stands in for is not
 * reproducible on demand: the loop's removal of the stale interest can land
 * after a later `bind()` has registered the same recycled fd number, silently
 * dropping a live listener so its `accept()` never fires. Rather than chase that
 * timing, this pins the invariant whose violation makes it reachable at all, and
 * the fd is registered through the same funnel every other registration uses.
 */
@OptIn(ExperimentalForeignApi::class)
class EpollBindRegistrationTest {

    /** Records WARN messages so the stale-interest complaint can be asserted on. */
    private class CapturingLogger(private val sink: MutableList<String>) : Logger {
        override fun isLoggable(level: LogLevel): Boolean = level == LogLevel.WARN

        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            if (level == LogLevel.WARN) sink.add(message.toString())
        }
    }

    @Test
    fun `a connection arriving before accept does not strand the listener interest`() = runBlocking {
        withTimeout(TEST_TIMEOUT) {
            val warnings = mutableListOf<String>()
            val engine = EpollEngine(config = IoEngineConfig(loggerFactory = { CapturingLogger(warnings) }))
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            // Connect without calling accept() first. The kernel completes the
            // handshake from the backlog, so the listener fd becomes readable
            // while no accept() waiter exists.
            val clientFd = connectRawClient(port)
            // Let the boss loop run an epoll_wait() cycle over the readable listener.
            usleep(BOSS_LOOP_SETTLE_MICROS)

            assertTrue(
                warnings.none { STALE_INTEREST_MARKER in it },
                "bind registered the listener with no handler behind it: $warnings",
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

        /** Substring of the no-handler WARN in [EpollEventLoop.dispatchReady]. */
        const val STALE_INTEREST_MARKER = "no handler"

        /** Long enough for the boss loop to wake, dispatch and log on loopback. */
        const val BOSS_LOOP_SETTLE_MICROS = 300_000u
    }
}
