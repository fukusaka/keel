package io.github.fukusaka.keel.logging

/**
 * Returns a [LoggerFactory] whose loggers never throw.
 *
 * [Logger] is a public SPI, so every log statement keel writes is a call into
 * code somebody else supplied. Most of those statements are reports made from
 * inside a `catch` that exists to keep going — an EventLoop finishing its batch
 * of tasks, a stop sweep telling one connection after another, a bind rolling
 * back the listeners it already opened. A logger that throws leaves such a
 * `catch` exactly the way the original failure would have, and the batch, the
 * sweep or the rollback stops there. The guard is defeated by the line written
 * to explain it, and the consequences are not small: on a `pthread` entry point
 * the escape ends the process, and on the NIO selector thread it takes every
 * connection on that loop with it.
 *
 * **The guarantee belongs here rather than at each call site.** Wrapping every
 * `logger.warn` in a `try` would be a convention to remember at some thirty
 * existing statements and every future one, and forgetting it is invisible
 * until a logger actually throws. Wrapping once, where the factory is read,
 * covers all of them and cannot be forgotten. It is also where the rest of the
 * ecosystem puts it: Log4j2's appenders take `ignoreExceptions`, defaulting to
 * true; Logback routes appender failures to its `StatusManager`; `java.util
 * .logging` hands them to an `ErrorManager`. Netty can write bare
 * `logger.warn` inside a `catch` precisely because its `InternalLogger` is its
 * own and not a pluggable SPI.
 *
 * **What is given up.** A log statement can no longer report failure to keel.
 * Nothing is lost by that: no call site in this library can act on a logging
 * failure, and the alternative is that it acts on it by abandoning whatever it
 * was in the middle of.
 *
 * **What is swallowed.** Anything the logger throws, including
 * `CancellationException` — a logger is not a suspending API and cancellation
 * does not travel through one, so a `CancellationException` from here is a
 * broken logger rather than a real cancellation, and letting it out would be
 * the escape this exists to stop.
 *
 * **The message lambda is not covered.** The inline extensions pass
 * `message()` as an argument to [Logger.rawLog], so it is evaluated at the
 * call site, before this guard is entered — a throwing message expression
 * escapes whenever the level is loggable. Keel's own expressions are
 * interpolation over values it already holds, which is what keeps that
 * unreached; closing it properly is tracked.
 *
 * Idempotent: wrapping a factory that is already wrapped returns it unchanged.
 */
public fun LoggerFactory.guarded(): LoggerFactory =
    if (this is GuardedLoggerFactory) this else GuardedLoggerFactory(this)

/** The wrapper [guarded] returns; see its KDoc for why this exists. */
@Suppress("SwallowedException", "TooGenericExceptionCaught")
private class GuardedLoggerFactory(private val delegate: LoggerFactory) : LoggerFactory {

    override fun logger(tag: String): Logger =
        try {
            GuardedLogger(delegate.logger(tag))
        } catch (factoryFailure: Throwable) {
            // Creating the logger is the same kind of call as using it, and a
            // caller asking for one is usually in the middle of building an
            // engine. Falling back keeps that construction alive, silently, on
            // the same reasoning as the rest of this file.
            NoopLoggerFactory.logger(tag)
        }
}

/**
 * Delegates to [delegate] and contains anything it throws.
 *
 * [isLoggable] answers `false` when it throws, which suppresses the statement
 * rather than routing it to a [rawLog] the same object is unlikely to survive —
 * and keeps the inline extensions from evaluating their message lambda.
 */
@Suppress("SwallowedException", "TooGenericExceptionCaught")
private class GuardedLogger(private val delegate: Logger) : Logger {

    override fun isLoggable(level: LogLevel): Boolean =
        try {
            delegate.isLoggable(level)
        } catch (loggerFailure: Throwable) {
            false
        }

    override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
        try {
            delegate.rawLog(level, throwable, message)
        } catch (loggerFailure: Throwable) {
            // Deliberately nothing: the only way to report this is the logger
            // that just produced it.
        }
    }
}
