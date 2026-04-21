package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.warn
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Closes [fd] via [NativeSocket.close] and emits a warn-level log if
 * the underlying `close(2)` returned non-zero.
 *
 * Used in error-cleanup paths so a close failure is observable without
 * masking the original exception that triggered the cleanup. [context]
 * is appended to the log message to identify the callsite (for example
 * `"connect cleanup"` or `"server close"`).
 *
 * Delegating through [NativeSocket] keeps every production `close(fd)`
 * call on the same interface that drives the other syscalls — fake
 * impls used in tests can track fd lifecycle uniformly, and the EINTR
 * handling policy is defined in exactly one place (see
 * [NativeSocket.close] KDoc for why `close(2)` is never retried).
 */
@OptIn(ExperimentalForeignApi::class)
fun closeFdSafely(fd: Int, logger: Logger, context: String) {
    when (val result = PosixNativeSocket.close(fd)) {
        CloseResult.Ok -> Unit
        is CloseResult.Failed ->
            logger.warn { "close($fd) failed during $context: ${errnoMessage(result.errno)}" }
    }
}
