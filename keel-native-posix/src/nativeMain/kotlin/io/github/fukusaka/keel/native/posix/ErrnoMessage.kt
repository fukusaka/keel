package io.github.fukusaka.keel.native.posix

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import posix_socket.keel_errno_message

/**
 * Returns a human-readable message for a POSIX errno value.
 *
 * Thread-safe: delegates to `keel_errno_message` (see `posix_socket.def`),
 * which wraps `strerror_r(3)` and normalises the XSI / GNU signature
 * differences. The underlying `strerror(3)` is not required to be
 * thread-safe and may race on older glibc or non-glibc libc.
 *
 * The returned message includes the numeric errno in parentheses so both
 * the symbolic description and the raw value appear in logs.
 *
 * @param errno POSIX errno (e.g. from `platform.posix.errno`, or the
 *              negated return of a Linux syscall).
 * @return Message such as `"Operation not permitted (errno=1)"`, or
 *         `"Unknown error (errno=N)"` if the lookup fails.
 */
@OptIn(ExperimentalForeignApi::class)
fun errnoMessage(errno: Int): String = memScoped {
    val bufferSize = ERRNO_BUFFER_SIZE
    val buf = allocArray<ByteVar>(bufferSize)
    val rc = keel_errno_message(errno, buf, bufferSize.convert())
    val message = if (rc == 0) buf.toKString() else "Unknown error"
    "$message (errno=$errno)"
}

private const val ERRNO_BUFFER_SIZE = 256
