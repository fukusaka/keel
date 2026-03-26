package io.github.fukusaka.keel.logging

/**
 * Minimal logging interface for keel internals.
 *
 * Implementations decide how and where log messages are written.
 * The level-specific extension functions ([trace], [debug], [info],
 * [warn], [error]) are `inline` so that the message lambda is never
 * allocated when the level is disabled.
 *
 * @see LoggerFactory
 * @see NoopLoggerFactory
 * @see PrintLogger
 */
public interface Logger {

    /** Returns `true` if this logger will emit messages at [level]. */
    public fun isLoggable(level: LogLevel): Boolean

    /**
     * Writes a single log record.
     *
     * Callers should prefer the inline extension functions ([trace],
     * [debug], etc.) which guard this call with [isLoggable].
     */
    public fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?)
}

/** Logs at [LogLevel.TRACE]. The [message] lambda is not evaluated when TRACE is disabled. */
public inline fun Logger.trace(message: () -> Any?) {
    if (isLoggable(LogLevel.TRACE)) rawLog(LogLevel.TRACE, null, message())
}

/** Logs at [LogLevel.DEBUG]. The [message] lambda is not evaluated when DEBUG is disabled. */
public inline fun Logger.debug(message: () -> Any?) {
    if (isLoggable(LogLevel.DEBUG)) rawLog(LogLevel.DEBUG, null, message())
}

/** Logs at [LogLevel.INFO]. The [message] lambda is not evaluated when INFO is disabled. */
public inline fun Logger.info(message: () -> Any?) {
    if (isLoggable(LogLevel.INFO)) rawLog(LogLevel.INFO, null, message())
}

/** Logs at [LogLevel.WARN] with an optional [throwable]. */
public inline fun Logger.warn(throwable: Throwable? = null, message: () -> Any?) {
    if (isLoggable(LogLevel.WARN)) rawLog(LogLevel.WARN, throwable, message())
}

/** Logs at [LogLevel.ERROR] with an optional [throwable]. */
public inline fun Logger.error(throwable: Throwable? = null, message: () -> Any?) {
    if (isLoggable(LogLevel.ERROR)) rawLog(LogLevel.ERROR, throwable, message())
}
