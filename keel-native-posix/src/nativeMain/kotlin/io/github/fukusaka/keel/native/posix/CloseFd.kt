package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.warn
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.close
import platform.posix.errno

/**
 * Closes [fd] and emits a warn-level log if `close(2)` returned non-zero.
 *
 * Used in error-cleanup paths so a close failure is observable without
 * masking the original exception that triggered the cleanup. [context]
 * is appended to the log message to identify the callsite (for example
 * `"connect cleanup"` or `"server close"`).
 */
@OptIn(ExperimentalForeignApi::class)
fun closeFdSafely(fd: Int, logger: Logger, context: String) {
    if (close(fd) != 0) {
        logger.warn { "close($fd) failed during $context: ${errnoMessage(errno)}" }
    }
}
