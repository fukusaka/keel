package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.native.posix.PosixRawClient
import platform.posix.getpid

/**
 * Shared helpers + constants for the categorised `KqueueEngine*Test` files.
 *
 * Test category split:
 *
 * | file | scope |
 * |---|---|
 * | [KqueueEngineLifecycleTest] | engine create/close, server bind/close, error paths, close race, cancellation, UDS / IPv6 binding |
 * | [KqueueEngineReadWriteTest] | echo, large payload, multi-write, half-close, `asSuspendSource` / `asSuspendSink` |
 * | [KqueueEngineConnectTest]   | client `connect()` flows |
 * | [KqueueEngineConcurrencyTest] | concurrent reads, concurrent accepts, `CoroutineDispatcher` integration, multi-thread `EventLoop` |
 * | [KqueueEngineResourceTest]  | `TrackingAllocator` leak detection + GC heap stability |
 * | [KqueueEnginePipelineTest]  | kqueue-specific pipeline / read-arm flow tests |
 *
 * Mirrors the same category split applied to the other engine test
 * suites (kqueue / epoll / io_uring / nio / netty / nwconnection /
 * nodejs). This file is the concrete realisation for the kqueue engine.
 */

// Per-operation hang-detection timeout for tests that exercise
// accept / read / job completion. Short enough to surface a real
// hang (normal latency on loopback is <10ms) but long enough not to
// flake on CI runners under load.
internal const val IO_OP_TIMEOUT_MS = 5_000L
internal const val IO_OP_SHORT_TIMEOUT_MS = 3_000L

// UDS path uniqueness counter — incremented per test that needs a
// unique filesystem path. Single-threaded test execution makes the
// non-atomic `var` safe; gradle test parallelism is per-class, not
// per-test, so concurrent increment is not possible.
private var udsPathSeq = 0

/**
 * Unique temp path so parallel test runs don't collide on the
 * filesystem. Caller must call [platform.posix.unlink] after close.
 */
internal fun uniqueUdsPath(): String {
    val pid = getpid()
    val seq = udsPathSeq++
    return "/tmp/keel-uds-$pid-$seq.sock"
}

// Thin facades over shared PosixRawClient (EINTR-safe via Layer 1,
// absolute-deadline SO_RCVTIMEO). Keeps test bodies unchanged from
// the pre-split monolith.

internal fun connectRawClient(port: Int): Int = PosixRawClient.rawConnect(port)

internal fun rawWrite(fd: Int, data: String): Unit = PosixRawClient.rawWrite(fd, data)

internal fun rawRead(fd: Int, size: Int): String = PosixRawClient.rawRead(fd, size)
