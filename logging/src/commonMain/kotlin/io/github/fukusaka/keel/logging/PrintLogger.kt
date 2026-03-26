package io.github.fukusaka.keel.logging

/**
 * Simple [Logger] and [LoggerFactory] that writes to standard output via [println].
 *
 * Intended for development and debugging. Production applications should
 * provide a [LoggerFactory] that bridges to their preferred logging framework
 * (e.g. SLF4J on JVM).
 *
 * Output format:
 * ```
 * [LEVEL] tag: message
 * [LEVEL] tag: message
 *   java.lang.Exception: cause detail
 *     at ...
 * ```
 *
 * @property tag   Identifies the source component (class name, module, etc.).
 * @property minLevel Minimum level to emit. Messages below this level are discarded.
 */
public class PrintLogger(
    public val tag: String,
    private val minLevel: LogLevel = LogLevel.TRACE,
) : Logger {

    override fun isLoggable(level: LogLevel): Boolean = level >= minLevel

    override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
        println("[${level.name}] $tag: $message")
        if (throwable != null) {
            println("  ${throwable.stackTraceToString().replace("\n", "\n  ")}")
        }
    }

    /**
     * [LoggerFactory] that creates [PrintLogger] instances with a shared [minLevel].
     *
     * ```
     * val engine = KqueueEngine(
     *     IoEngineConfig(loggerFactory = PrintLogger.Factory(LogLevel.DEBUG))
     * )
     * ```
     */
    public class Factory(
        private val minLevel: LogLevel = LogLevel.TRACE,
    ) : LoggerFactory {
        override fun logger(tag: String): Logger = PrintLogger(tag, minLevel)
    }
}
