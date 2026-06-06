package io.github.fukusaka.keel.codec.http

import kotlin.experimental.ExperimentalNativeApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal actual fun readBypassEnvVar(): Boolean =
    getenv("KEEL_BENCH_HTTP_HEADERS_POOL_BYPASS")?.toKString() == "1"
