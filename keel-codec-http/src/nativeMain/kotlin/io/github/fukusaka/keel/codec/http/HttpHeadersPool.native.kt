package io.github.fukusaka.keel.codec.http

import kotlin.native.concurrent.ThreadLocal

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
