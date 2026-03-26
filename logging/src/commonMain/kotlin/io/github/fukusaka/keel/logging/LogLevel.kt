package io.github.fukusaka.keel.logging

/**
 * Log severity levels, ordered from most verbose to most severe.
 *
 * Libraries typically log at [DEBUG] or [TRACE] for internal details,
 * reserving [WARN] and [ERROR] for actionable conditions.
 */
public enum class LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
}
