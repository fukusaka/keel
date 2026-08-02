package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.native.posix.PosixRawClient
import platform.posix.getpid
import kotlin.concurrent.AtomicReference

/**
 * Shared helpers + constants for the categorised `EpollEngine*Test` files.
 *
 * Test category split:
 *
 * | file | scope |
 * |---|---|
 * | [EpollEngineLifecycleTest] | engine create/close, server bind/close, error paths, close race, cancellation, UDS / IPv6 binding |
 * | [EpollEngineReadWriteTest] | echo, large payload, multi-write, half-close, `asSuspendSource` / `asSuspendSink` |
 * | [EpollEngineConnectTest]   | client `connect()` flows (refused / addresses / round-trip) |
 * | [EpollEngineConcurrencyTest] | concurrent reads, concurrent accepts, `CoroutineDispatcher` integration, multi-thread `EventLoop` |
 * | [EpollEngineResourceTest]  | `TrackingAllocator` leak detection + GC heap stability |
 *
 * Mirrors the same category split applied to the other engine test
 * suites (kqueue / epoll / io_uring / nio / netty / nwconnection /
 * nodejs). This file is the concrete realisation for the epoll engine.
 */

// Per-operation hang-detection timeout for tests that exercise
// accept / read / job completion. Short enough to surface a real
// hang (normal latency on loopback is <10ms) but long enough not to
// flake on CI runners under load.
internal const val IO_OP_TIMEOUT_MS = 5_000L
internal const val IO_OP_SHORT_TIMEOUT_MS = 3_000L

// A fixed port nothing listens on, for connection-refused tests.
// Port 1 (tcpmux) is outside the ephemeral range and unassigned in
// practice, so a loopback connect to it is reliably refused. A freed
// ephemeral port must NOT be used: a loopback connect to one can win a
// TCP simultaneous-open against the connecting socket's own auto-bound
// local port and succeed (self-connect), flaking the test.
internal const val REFUSED_PORT = 1

// UDS path uniqueness counter — incremented per test that needs a
// unique filesystem path. Safe as a non-atomic top-level `var` because
// the Kotlin/Native test runner (kotlin-test on linuxX64) executes
// tests sequentially in a single thread. Cross-process collision is
// handled by getpid() in the path.
private var udsPathSeq = 0

internal fun uniqueUdsPath(): String {
    val pid = getpid()
    val seq = udsPathSeq++
    return "/tmp/keel-uds-epoll-$pid-$seq.sock"
}

internal fun uniqueAbstractUdsName(): String {
    val pid = getpid()
    val seq = udsPathSeq++
    return "keel-epoll-abs-$pid-$seq"
}

// Thin facades over shared PosixRawClient (EINTR-safe via Layer 1,
// absolute-deadline SO_RCVTIMEO). Keeps test bodies unchanged from
// the pre-split monolith.

internal fun connectRawClient(port: Int): Int = PosixRawClient.rawConnect(port)

internal fun rawWrite(fd: Int, data: String): Unit = PosixRawClient.rawWrite(fd, data)

internal fun rawRead(fd: Int, size: Int): String = PosixRawClient.rawRead(fd, size)

/**
 * A [Logger] that records the messages logged at [captured] so a test can
 * assert on them.
 *
 * The engine logs from its EventLoop threads while the test asserts from its
 * own, so the sink is an immutable list swapped under CAS rather than a
 * `MutableList`: an unsynchronised list gives the reader no guarantee of
 * seeing what the loop appended, which would let an assertion on a missing
 * message pass for the wrong reason.
 */
internal class RecordingLogger(private val captured: LogLevel) : Logger {

    private val sink = AtomicReference<List<String>>(emptyList())

    /** Every message logged at [captured] so far, oldest first. */
    val messages: List<String> get() = sink.value

    override fun isLoggable(level: LogLevel): Boolean = level == captured

    override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
        if (level != captured) return
        val text = message.toString()
        while (true) {
            val current = sink.value
            if (sink.compareAndSet(current, current + text)) return
        }
    }
}

/**
 * Bind address for tests whose client connects over loopback.
 *
 * Not `0.0.0.0`: `SO_REUSEADDR` lets another process bind `127.0.0.1` on the
 * *same* port after this server is already listening on the wildcard, and a
 * connect to `127.0.0.1` is then delivered to that later, more specific
 * listener instead of to the test's server. Binding loopback here makes the
 * second bind fail with `EADDRINUSE`, so the port cannot be taken over.
 * (Measured both ways; the kernel does not hand an occupied port to a wildcard
 * bind, so the exposure is the takeover, not the initial allocation. Observed:
 * an SSE test read `503 Proxy key is incorrect` from an IDE's proxy that had
 * taken the port out from under the test server.)
 */
internal const val LOOPBACK_HOST: String = "127.0.0.1"
