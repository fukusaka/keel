package io.github.fukusaka.keel.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoggerTest {

    // -- NoopLoggerFactory --

    @Test
    fun `NoopLoggerFactory returns logger that is never loggable`() {
        val logger = NoopLoggerFactory.logger("test")
        for (level in LogLevel.entries) {
            assertFalse(logger.isLoggable(level))
        }
    }

    @Test
    fun `NoopLoggerFactory rawLog does not throw`() {
        val logger = NoopLoggerFactory.logger("test")
        logger.rawLog(LogLevel.ERROR, RuntimeException("ignored"), "message")
    }

    // -- PrintLogger --

    @Test
    fun `PrintLogger isLoggable respects minLevel`() {
        val logger = PrintLogger("tag", LogLevel.WARN)
        assertFalse(logger.isLoggable(LogLevel.TRACE))
        assertFalse(logger.isLoggable(LogLevel.DEBUG))
        assertFalse(logger.isLoggable(LogLevel.INFO))
        assertTrue(logger.isLoggable(LogLevel.WARN))
        assertTrue(logger.isLoggable(LogLevel.ERROR))
    }

    @Test
    fun `PrintLogger default minLevel is TRACE`() {
        val logger = PrintLogger("tag")
        for (level in LogLevel.entries) {
            assertTrue(logger.isLoggable(level))
        }
    }

    @Test
    fun `PrintLogger Factory creates loggers with shared minLevel`() {
        val factory = PrintLogger.Factory(LogLevel.INFO)
        val logger = factory.logger("component")
        assertFalse(logger.isLoggable(LogLevel.DEBUG))
        assertTrue(logger.isLoggable(LogLevel.INFO))
        assertTrue(logger.isLoggable(LogLevel.ERROR))
    }

    // -- inline extension guard --

    @Test
    fun `inline extensions do not evaluate message when level is disabled`() {
        val logger = RecordingLogger(LogLevel.WARN)
        var evaluated = false

        logger.trace { evaluated = true; "trace" }
        assertFalse(evaluated, "trace lambda should not be evaluated when minLevel=WARN")

        logger.debug { evaluated = true; "debug" }
        assertFalse(evaluated, "debug lambda should not be evaluated when minLevel=WARN")

        logger.info { evaluated = true; "info" }
        assertFalse(evaluated, "info lambda should not be evaluated when minLevel=WARN")

        logger.warn { evaluated = true; "warn" }
        assertTrue(evaluated, "warn lambda should be evaluated when minLevel=WARN")
    }

    @Test
    fun `inline extensions pass throwable for warn and error`() {
        val logger = RecordingLogger(LogLevel.TRACE)
        val cause = RuntimeException("boom")

        logger.warn(cause) { "warning" }
        assertEquals(cause, logger.lastThrowable)

        logger.error(cause) { "error" }
        assertEquals(cause, logger.lastThrowable)
    }

    @Test
    fun `inline extensions pass null throwable for trace debug info`() {
        val logger = RecordingLogger(LogLevel.TRACE)

        logger.trace { "t" }
        assertEquals(null, logger.lastThrowable)

        logger.debug { "d" }
        assertEquals(null, logger.lastThrowable)

        logger.info { "i" }
        assertEquals(null, logger.lastThrowable)
    }

    // -- test helper --

    private class RecordingLogger(private val minLevel: LogLevel) : Logger {
        var lastLevel: LogLevel? = null
        var lastThrowable: Throwable? = null
        var lastMessage: Any? = null

        override fun isLoggable(level: LogLevel): Boolean = level >= minLevel

        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            lastLevel = level
            lastThrowable = throwable
            lastMessage = message
        }
    }
}
