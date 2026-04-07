package io.github.fukusaka.keel.logging

/**
 * Creates [Logger] instances identified by a tag (typically a class name).
 *
 * Set on `IoEngineConfig.loggerFactory` to enable logging for an engine
 * and all channels it creates.
 * The default is [NoopLoggerFactory] which discards all log output.
 */
public fun interface LoggerFactory {
    /** Returns a [Logger] for the given [tag]. */
    public fun logger(tag: String): Logger
}

/**
 * Default [LoggerFactory] that discards all log output.
 *
 * The returned [Logger.isLoggable] always returns `false`, so inline
 * extension functions never evaluate the message lambda — zero overhead.
 */
public object NoopLoggerFactory : LoggerFactory {

    private val NOOP = object : Logger {
        override fun isLoggable(level: LogLevel): Boolean = false
        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {}
    }

    override fun logger(tag: String): Logger = NOOP
}
