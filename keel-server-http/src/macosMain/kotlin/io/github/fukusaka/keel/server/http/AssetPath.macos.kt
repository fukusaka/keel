package io.github.fukusaka.keel.server.http

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.stat
import kotlin.time.Instant

/** Milliseconds per second — for converting `timespec` seconds to epoch millis. */
private const val MILLIS_PER_SECOND = 1_000L

/** Nanoseconds per millisecond — for the sub-second part of `timespec`. */
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Reads the file modification time via POSIX `stat(2)`, returning null
 * when the call fails (missing file, permission denied).
 *
 * macOS's `struct stat` carries the modification time in the
 * `st_mtimespec` `timespec` field — distinct from Linux's `st_mtim`,
 * hence a macOS-specific actual.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun assetLastModified(path: String): Instant? = memScoped {
    val info = alloc<stat>()
    if (stat(path, info.ptr) != 0) return null
    val mtim = info.st_mtimespec
    val millis = mtim.tv_sec * MILLIS_PER_SECOND + mtim.tv_nsec / NANOS_PER_MILLI
    Instant.fromEpochMilliseconds(millis)
}
