package io.github.fukusaka.keel.engine.iouring

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.errno
import posix_inet.keel_eventfd_create
import posix_inet.keel_eventfd_write

/**
 * Production implementation of [IoUringSyscallOps] that delegates to the
 * `keel_eventfd_*` C wrappers in `posix_inet.def`. Stateless singleton.
 *
 * Per the [IoUringSyscallOps] contract, methods translate the raw
 * `return -1 + errno` syscall convention into the Kotlin-side encoding
 * (`negative -errno` for fd-returning calls, positive errno for ok/err
 * calls). This lets callers inspect errno without reading
 * `platform.posix.errno` — important because `FakeIoUringSyscallOps`
 * cannot set the real thread-local errno.
 */
@OptIn(ExperimentalForeignApi::class)
internal object PosixIoUringSyscallOps : IoUringSyscallOps {

    override fun eventfdCreate(): Int {
        val fd = keel_eventfd_create()
        return if (fd < 0) -errno else fd
    }

    override fun eventfdWakeupWrite(eventfd: Int): Int {
        val rc = keel_eventfd_write(eventfd)
        return if (rc < 0) errno else 0
    }
}
