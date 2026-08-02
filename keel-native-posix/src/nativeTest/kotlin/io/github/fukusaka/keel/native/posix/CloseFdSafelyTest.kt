package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import platform.posix.close
import platform.posix.dup
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct unit tests for [closeFdSafely] — the error-cleanup helper that
 * closes an fd and emits a warn-level log (instead of throwing) when the
 * underlying `close(2)` fails, so a close failure stays observable
 * without masking the original exception that triggered the cleanup.
 *
 * These run against the real `close(2)` syscall (via the production
 * `PosixNativeSocket.close` that [closeFdSafely] delegates to) because
 * the helper hard-wires that singleton rather than taking an injectable
 * [NativeSocket]. The kernel interactions used here are deterministic:
 * `dup(2)` of an open standard stream yields a fresh closable fd, and
 * `close(2)` of `-1` / an already-closed fd fails with `EBADF`.
 *
 * A local [CapturingLogger] records emitted records so the warn path can
 * be asserted on level + message content.
 */
class CloseFdSafelyTest {

    @Test
    fun `closeFdSafely on a valid fd closes it without logging`() {
        val logger = CapturingLogger()
        // dup(2) of stdout yields a brand-new fd that we own and can
        // close exactly once — the happy path.
        val fd = dup(STDOUT_FD)
        assertTrue(fd >= 0, "dup(STDOUT) should hand out a valid fd, got $fd")

        closeFdSafely(fd, logger, "valid-fd cleanup")

        assertTrue(
            logger.records.isEmpty(),
            "a successful close must not log anything, got: ${logger.records}",
        )
    }

    @Test
    fun `closeFdSafely on an invalid fd emits a warn log with context and errno`() {
        val logger = CapturingLogger()

        // close(-1) fails with EBADF; the helper must swallow the failure
        // and log it at WARN instead of throwing.
        closeFdSafely(-1, logger, "connect cleanup")

        assertEquals(1, logger.records.size, "exactly one record expected, got: ${logger.records}")
        val (level, message) = logger.records.single()
        assertEquals(LogLevel.WARN, level, "close failure must be logged at WARN")
        assertContains(message, "connect cleanup")
        assertContains(message, "close(-1) failed")
        assertContains(message, "errno=")
    }

    @Test
    fun `closeFdSafely on an already-closed fd emits a warn log`() {
        val logger = CapturingLogger()
        val fd = dup(STDOUT_FD)
        assertTrue(fd >= 0, "dup(STDOUT) should hand out a valid fd, got $fd")
        // Close it out from under the helper so the helper's own close
        // hits EBADF — the double-close cleanup scenario.
        assertEquals(0, close(fd), "precondition: first close should succeed")

        closeFdSafely(fd, logger, "double-close cleanup")

        assertEquals(1, logger.records.size, "exactly one record expected, got: ${logger.records}")
        val (level, message) = logger.records.single()
        assertEquals(LogLevel.WARN, level, "close failure must be logged at WARN")
        assertContains(message, "double-close cleanup")
        assertContains(message, "close($fd) failed")
    }

    /**
     * Records every [rawLog] call as a `(level, message)` pair. Always
     * loggable so the WARN branch in [closeFdSafely] fires.
     */
    private class CapturingLogger : Logger {

        val records = mutableListOf<Pair<LogLevel, String>>()

        override fun isLoggable(level: LogLevel): Boolean = true

        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            records.add(level to message.toString())
        }
    }

    private companion object {
        // STDOUT_FILENO — duplicating it is a portable way to obtain a
        // fresh, owned fd without opening a file.
        private const val STDOUT_FD = 1
    }
}
