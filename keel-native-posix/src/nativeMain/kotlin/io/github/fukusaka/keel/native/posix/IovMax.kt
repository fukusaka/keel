package io.github.fukusaka.keel.native.posix

import kotlinx.cinterop.ExperimentalForeignApi
import posix_socket.keel_iov_max

/**
 * The kernel's per-call limit on the number of regions a gather write may
 * offer — `IOV_MAX` on macOS, `UIO_MAXIOV` on glibc, 1024 on both.
 *
 * Resolved once when this file's properties initialise rather than per
 * flush: the value is a kernel build constant, and the gather path consults
 * it on every call.
 *
 * **Offering more is not a partial write — nothing is sent and the call
 * fails.** Which errno says so depends on the syscall underneath: measured
 * at `IOV_MAX + 1` regions, `writev(2)` answers `EINVAL` and `sendmsg(2)` —
 * what the Linux build issues, for its per-call `SIGPIPE` suppression —
 * answers `EMSGSIZE`. Neither distinguishes "too many regions" from the
 * other ways that errno arises, so a caller cannot classify its way out
 * afterwards; it has to keep each batch within this bound and issue another
 * call for the rest.
 */
@OptIn(ExperimentalForeignApi::class)
public val IOV_MAX: Int = keel_iov_max()
