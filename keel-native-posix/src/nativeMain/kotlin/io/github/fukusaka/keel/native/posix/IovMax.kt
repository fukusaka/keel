package io.github.fukusaka.keel.native.posix

import kotlinx.cinterop.ExperimentalForeignApi
import posix_socket.keel_iov_max

/**
 * The kernel's per-call limit on the number of regions a gather write may
 * offer — `IOV_MAX` on macOS, `UIO_MAXIOV` on glibc, 1024 on both.
 *
 * Read once at class-init rather than per flush: the value is a kernel build
 * constant, and the gather path consults it on every call.
 *
 * **Offering more is not a partial write — it is `EINVAL` with nothing
 * sent.** The errno does not distinguish "too many regions" from any other
 * argument error, so a caller cannot classify its way out afterwards; it has
 * to keep each batch within this bound and issue another call for the rest.
 */
@OptIn(ExperimentalForeignApi::class)
public val IOV_MAX: Int = keel_iov_max()
