package io.github.fukusaka.keel.native.posix

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Parses an IPv4 address string into binary form.
 *
 * Wraps `inet_pton(AF_INET, src, dst)`. On Linux, delegates to a
 * C wrapper (`keel_inet_pton`) for reliable cinterop binding.
 * On macOS, calls `platform.darwin.inet_pton` directly.
 *
 * @return 1 on success, 0 if [src] is not a valid address.
 */
@OptIn(ExperimentalForeignApi::class)
expect fun inetPton(af: Int, src: String, dst: CValuesRef<*>): Int

/**
 * Formats a binary IPv4 address into a string.
 *
 * Wraps `inet_ntop(AF_INET, src, dst, size)`. On Linux, delegates to
 * a C wrapper (`keel_inet_ntop`). On macOS, calls `platform.darwin.inet_ntop`.
 *
 * @return pointer to [dst] on success, null on failure.
 */
@OptIn(ExperimentalForeignApi::class)
expect fun inetNtop(af: Int, src: CValuesRef<*>, dst: CPointer<ByteVar>, size: UInt): CPointer<ByteVar>?
