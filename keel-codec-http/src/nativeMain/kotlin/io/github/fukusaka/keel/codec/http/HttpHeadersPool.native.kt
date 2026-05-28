package io.github.fukusaka.keel.codec.http

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.concurrent.ThreadLocal
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Per-thread pool stack backed by Kotlin/Native [@ThreadLocal][ThreadLocal].
 * A top-level `@ThreadLocal` value gets a distinct copy per OS thread,
 * including the raw pthreads that keel's epoll / kqueue / io_uring
 * EventLoops are created on (`pthread_create` + `staticCFunction`).
 *
 * `@ThreadLocal` isolation on pthread-created threads — not just Kotlin
 * `Worker`s — is verified by `NativeConcurrencyProbeTest`.
 */
@ThreadLocal
private val nativeStack: ArrayDeque<HttpHeaders> = ArrayDeque()

internal actual fun headersPoolStack(): ArrayDeque<HttpHeaders> = nativeStack

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal actual fun readBypassEnvVar(): Boolean =
    getenv("KEEL_BENCH_HTTP_HEADERS_POOL_BYPASS")?.toKString() == "1"
