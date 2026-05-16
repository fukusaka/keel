package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.native.posix.PosixRawClient
import platform.posix.getpid

/**
 * Shared helpers + constants for the categorised `IoUringEngine*Test` files.
 *
 * Test category split:
 *
 * | file | scope |
 * |---|---|
 * | [IoUringEngineLifecycleTest] | engine create/close, server bind/close, error paths, double close, UDS / IPv6 binding, write-zero edge case |
 * | [IoUringEngineReadWriteTest] | echo, large payload, multi-write, half-close, `asSuspendSource` (multishot recv) / `asSuspendSink` |
 * | [IoUringEngineConnectTest]   | client `connect()` flows (refused / hostname / round-trip) |
 * | [IoUringEngineResourceTest]  | `IoBuf` leak detection (closed channel with pending writes / echo) |
 * | [IoUringEngineUringSpecificTest] | io_uring-specific: wakeup SQE retry, ASYNC_CANCEL, multishot accept flows, round-robin EventLoop assignment, close-while-armed |
 *
 * Mirrors the same category split applied to the other engine test
 * suites (kqueue / epoll / io_uring / nio / netty / nwconnection /
 * nodejs). This file is the concrete realisation for the io_uring engine.
 */

// Per-operation hang-detection timeout for tests that exercise
// accept / read / job completion. Short enough to surface a real
// hang (normal latency on loopback is <10ms) but long enough not to
// flake on CI runners under load.
internal const val IO_OP_TIMEOUT_MS = 5_000L
// Shorter variant for ops expected to complete quickly on the happy
// path (e.g. a read that should already have data queued).
internal const val IO_OP_SHORT_TIMEOUT_MS = 3_000L
// Hang-detection bound for dispatch-await regression tests: if the EL
// has not processed a cross-thread dispatch within this window the test
// is treated as a deadlock regression. The healthy path completes in
// well under 10 ms; the budget matches IO_OP_TIMEOUT_MS so a starved CI
// runner's scheduling jitter cannot push a healthy run past it (a 2 s
// budget flaked exactly that way), while a true deadlock — which never
// completes — is still caught.
internal const val DISPATCH_AWAIT_TIMEOUT_MS = 5_000L

// A fixed port nothing listens on, for connection-refused tests.
// Port 1 (tcpmux) is outside the ephemeral range and unassigned in
// practice, so a loopback connect to it is reliably refused. A freed
// ephemeral port must NOT be used: a loopback connect to one can win a
// TCP simultaneous-open against the connecting socket's own auto-bound
// local port and succeed (self-connect), flaking the test.
internal const val REFUSED_PORT = 1

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

internal fun uniqueAbstractUdsName(): String {
    val pid = getpid()
    val seq = udsPathSeq++
    return "keel-test-abstract-$pid-$seq"
}

// Thin facades over shared PosixRawClient (EINTR-safe via Layer 1,
// absolute-deadline SO_RCVTIMEO). Keeps test bodies unchanged from
// the pre-split monolith.

internal fun connectRawClient(port: Int): Int = PosixRawClient.rawConnect(port)

internal fun rawWrite(fd: Int, data: String): Unit = PosixRawClient.rawWrite(fd, data)

internal fun rawRead(fd: Int, size: Int): String = PosixRawClient.rawRead(fd, size)
