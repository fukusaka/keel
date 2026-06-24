@file:OptIn(ExperimentalForeignApi::class)

package io.github.fukusaka.keel.buf

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.pthread_self

/**
 * Linux `pthread_self()` returns a `ULong` thread handle; reinterpreted to [Long]
 * and used only for equality (keel EventLoops are pthread-pinned, so this is a
 * truthful per-EventLoop identity).
 */
internal actual fun currentThreadId(): Long = pthread_self().toLong()
