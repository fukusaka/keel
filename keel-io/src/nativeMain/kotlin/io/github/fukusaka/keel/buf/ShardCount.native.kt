@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.fukusaka.keel.buf

import platform.posix._SC_NPROCESSORS_ONLN
import platform.posix.sysconf

internal actual fun availableProcessors(): Int = sysconf(_SC_NPROCESSORS_ONLN).toInt().coerceAtLeast(1)
