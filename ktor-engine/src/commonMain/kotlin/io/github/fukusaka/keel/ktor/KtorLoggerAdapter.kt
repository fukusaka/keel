package io.github.fukusaka.keel.ktor

import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.LoggerFactory
import io.ktor.util.logging.isDebugEnabled
import io.ktor.util.logging.isTraceEnabled
import io.ktor.util.logging.Logger as KtorLogger

/**
 * Adapts a Ktor [KtorLogger] to the keel [Logger] interface.
 *
 * Level checks delegate to [KtorLogger.isTraceEnabled] and
 * [KtorLogger.isDebugEnabled]. INFO/WARN/ERROR are always enabled
 * because Ktor's Logger does not expose level checks above DEBUG.
 */
internal class KtorLoggerAdapter(
    private val ktor: KtorLogger,
) : Logger {

    override fun isLoggable(level: LogLevel): Boolean = when (level) {
        LogLevel.TRACE -> ktor.isTraceEnabled
        LogLevel.DEBUG -> ktor.isDebugEnabled
        // Ktor Logger does not expose isInfoEnabled/isWarnEnabled/isErrorEnabled
        LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR -> true
    }

    override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
        val msg = message?.toString() ?: ""
        when (level) {
            LogLevel.TRACE -> if (throwable != null) ktor.trace(msg, throwable) else ktor.trace(msg)
            LogLevel.DEBUG -> if (throwable != null) ktor.debug(msg, throwable) else ktor.debug(msg)
            LogLevel.INFO -> if (throwable != null) ktor.info(msg, throwable) else ktor.info(msg)
            LogLevel.WARN -> if (throwable != null) ktor.warn(msg, throwable) else ktor.warn(msg)
            LogLevel.ERROR -> if (throwable != null) ktor.error(msg, throwable) else ktor.error(msg)
        }
    }
}

/**
 * [LoggerFactory] that bridges to a Ktor [KtorLogger].
 *
 * All loggers created by this factory delegate to the same Ktor logger
 * (typically [ApplicationEnvironment.log][io.ktor.server.application.ApplicationEnvironment.log]).
 */
internal class KtorLoggerFactory(
    private val ktor: KtorLogger,
) : LoggerFactory {
    override fun logger(tag: String): Logger = KtorLoggerAdapter(ktor)
}
