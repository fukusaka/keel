package io.github.fukusaka.keel.engine.iouring

import io_uring.keel_kernel_version_major
import io_uring.keel_kernel_version_minor
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Linux kernel version (major.minor) for capability detection.
 *
 * Retrieved via `uname(2)` syscall. Used by [IoUringCapabilities.detect]
 * to determine which io_uring features are available on the running kernel.
 */
@OptIn(ExperimentalForeignApi::class)
data class KernelVersion(val major: Int, val minor: Int) : Comparable<KernelVersion> {
    override fun compareTo(other: KernelVersion): Int {
        val m = major.compareTo(other.major)
        return if (m != 0) m else minor.compareTo(other.minor)
    }

    override fun toString(): String = "$major.$minor"

    companion object {
        /** Returns the running kernel's version. */
        fun current(): KernelVersion =
            KernelVersion(keel_kernel_version_major(), keel_kernel_version_minor())
    }
}
