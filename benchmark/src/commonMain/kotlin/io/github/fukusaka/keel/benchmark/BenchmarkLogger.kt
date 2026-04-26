package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.LoggerFactory
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.logging.PrintLogger

/**
 * Resolves the [LoggerFactory] for keel pipeline benchmark engines based
 * on the `KEEL_BENCH_LOG_LEVEL` environment variable.
 *
 * Default (env unset) is [NoopLoggerFactory] — engines pay nothing for
 * logging, which is the right setting for throughput measurements.
 *
 * Setting `KEEL_BENCH_LOG_LEVEL=debug` switches to [PrintLogger.Factory]
 * with [LogLevel.DEBUG], which is required for the slow-path bench
 * scenarios to surface the per-transport `flush=` / `partial=` /
 * `ratio_bp=` summary that
 * [io.github.fukusaka.keel.pipeline.AbstractIoTransport] emits at debug
 * level on transport teardown. `bench-remote-slow.sh` scrapes those
 * lines from the server log to verify a scenario actually exercises the
 * partial-write path.
 *
 * Recognised values (case-insensitive): `trace`, `debug`, `info`,
 * `warn`, `error`. Any other value falls back to [NoopLoggerFactory]
 * so a typo never silently degrades a perf benchmark by enabling
 * verbose logging.
 */
fun benchmarkLoggerFactory(): LoggerFactory {
    val level = getEnvVar("KEEL_BENCH_LOG_LEVEL")?.lowercase() ?: return NoopLoggerFactory
    val logLevel = when (level) {
        "trace" -> LogLevel.TRACE
        "debug" -> LogLevel.DEBUG
        "info" -> LogLevel.INFO
        "warn" -> LogLevel.WARN
        "error" -> LogLevel.ERROR
        else -> {
            // Typo guard: unknown value falls back to noop rather than
            // accidentally enabling per-event logging on a hot bench.
            return NoopLoggerFactory
        }
    }
    return PrintLogger.Factory(minLevel = logLevel)
}
