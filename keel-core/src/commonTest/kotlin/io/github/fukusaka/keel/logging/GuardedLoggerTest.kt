package io.github.fukusaka.keel.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for the guarantee [guarded] adds to the [Logger] SPI.
 *
 * The statements keel writes are mostly reports made from inside a `catch` that
 * exists to keep going. A logger that throws leaves that `catch` the way the
 * original failure would have, and whatever the guard was protecting stops
 * there. These pin that a wrapped factory cannot do that, and that a
 * well-behaved logger is not otherwise disturbed.
 */
internal class GuardedLoggerTest {

    private class ThrowingLogger(
        private val throwFromIsLoggable: Boolean = false,
        private val throwFromRawLog: Boolean = false,
    ) : Logger {
        var isLoggableCalls = 0
        var rawLogCalls = 0

        override fun isLoggable(level: LogLevel): Boolean {
            isLoggableCalls++
            if (throwFromIsLoggable) throw IllegalStateException("isLoggable boom")
            return true
        }

        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            rawLogCalls++
            if (throwFromRawLog) throw IllegalStateException("rawLog boom")
        }
    }

    private class RecordingLogger : Logger {
        val logged = mutableListOf<Triple<LogLevel, Throwable?, Any?>>()
        override fun isLoggable(level: LogLevel) = level != LogLevel.TRACE
        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            logged.add(Triple(level, throwable, message))
        }
    }

    @Test
    fun `a logger that throws from rawLog does not throw to the caller`() {
        val logger = LoggerFactory { ThrowingLogger(throwFromRawLog = true) }.guarded().logger("t")

        logger.warn(IllegalStateException("original")) { "report" }

        // No assertion beyond "we got here": the whole contract is that the
        // statement above returns rather than unwinding its caller's `catch`.
    }

    @Test
    fun `a logger that throws from isLoggable suppresses the statement instead`() {
        val delegate = ThrowingLogger(throwFromIsLoggable = true)
        val logger = LoggerFactory { delegate }.guarded().logger("t")
        var messageEvaluated = false

        logger.warn {
            messageEvaluated = true
            "report"
        }

        assertFalse(messageEvaluated, "a level check that fails must not go on to build the message")
        assertEquals(0, delegate.rawLogCalls, "nor route the statement to a logger that just failed")
    }

    @Test
    fun `a factory that throws yields a logger rather than failing construction`() {
        val factory = LoggerFactory { error("factory boom") }.guarded()

        val logger = factory.logger("t")

        assertFalse(logger.isLoggable(LogLevel.ERROR), "the fallback discards rather than pretends")
        logger.warn { "report" }
    }

    @Test
    fun `a well-behaved logger sees every argument unchanged`() {
        val delegate = RecordingLogger()
        val logger = LoggerFactory { delegate }.guarded().logger("t")
        val cause = IllegalStateException("original")

        logger.warn(cause) { "report" }

        assertEquals(1, delegate.logged.size)
        val (level, throwable, message) = delegate.logged.single()
        assertEquals(LogLevel.WARN, level)
        assertSame(cause, throwable, "the throwable must reach the logger, not a copy")
        assertEquals("report", message)
    }

    @Test
    fun `the level check still short-circuits the message lambda`() {
        val delegate = RecordingLogger() // TRACE is not loggable
        val logger = LoggerFactory { delegate }.guarded().logger("t")
        var messageEvaluated = false

        logger.trace {
            messageEvaluated = true
            "report"
        }

        assertFalse(messageEvaluated, "wrapping must not cost the zero-overhead disabled path")
        assertTrue(delegate.logged.isEmpty())
    }

    @Test
    fun `wrapping an already wrapped factory returns it unchanged`() {
        val once = LoggerFactory { RecordingLogger() }.guarded()

        assertSame(once, once.guarded(), "a second wrap would add a layer per engine that reads it")
    }
}
