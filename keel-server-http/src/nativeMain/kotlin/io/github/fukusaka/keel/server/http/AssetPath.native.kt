package io.github.fukusaka.keel.server.http

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.PATH_MAX
import platform.posix.realpath

/**
 * Canonicalizes the path with POSIX `realpath(3)`, resolving symlinks
 * and `.`/`..`; returns null when the path does not exist or
 * `realpath` fails.
 *
 * `realpath` has identical semantics on Linux and macOS, so a single
 * `nativeMain` actual covers both.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun canonicalizeAssetPath(path: String): String? = memScoped {
    val resolved = allocArray<ByteVar>(PATH_MAX)
    val result = realpath(path, resolved) ?: return null
    result.toKString()
}
