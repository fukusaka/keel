@file:OptIn(ExperimentalForeignApi::class)

package io.github.fukusaka.keel.buf

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toLong
import platform.posix.pthread_self

/**
 * Apple `pthread_self()` returns an opaque `pthread_t` pointer; its address is a
 * stable per-thread identifier, used only for equality. NWConnection's GCD serial
 * queue migrates across worker pthreads, so this can over-count cross-thread
 * releases for NW-backed buffers — a false positive, the safe direction (see
 * [CrossThreadReleaseProfile]).
 */
internal actual fun currentThreadId(): Long = pthread_self()?.toLong() ?: 0L
