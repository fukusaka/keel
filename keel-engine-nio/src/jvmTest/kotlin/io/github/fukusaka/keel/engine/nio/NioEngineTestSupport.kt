package io.github.fukusaka.keel.engine.nio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Shared helpers + constants for the categorised `NioEngine*Test` files.
 *
 * Test category split:
 *
 * | file | scope |
 * |---|---|
 * | [NioEngineLifecycleTest] | engine create/close, server bind/close, error paths, double close, UDS binding |
 * | [NioEngineReadWriteTest] | echo, large payload, multi-write, half-close, `asSuspendSource` / `asSuspendSink`, SelectionKey reuse |
 * | [NioEngineConnectTest]   | client `connect()` flows |
 * | [NioEngineConcurrencyTest] | concurrent reads, FIFO accept queue, close race, cancellation |
 * | [NioEngineResourceTest]  | `TrackingAllocator` leak detection |
 *
 * Mirrors the same category split applied to the other engine test
 * suites (kqueue / epoll / io_uring / nio / netty / nwconnection /
 * nodejs). This file is the concrete realisation for the NIO engine.
 */

internal val TEST_TIMEOUT = 10.seconds

internal fun runTest(block: suspend CoroutineScope.() -> Unit) =
    runBlocking { withTimeout(TEST_TIMEOUT, block) }

// Per-operation hang-detection timeout for tests that exercise
// accept / read / job completion. Short enough to surface a real
// hang (normal latency on loopback is <10ms) but long enough not to
// flake on CI runners under load.
internal const val IO_OP_TIMEOUT_MS = 5_000L
internal const val IO_OP_SHORT_TIMEOUT_MS = 3_000L

// Longer bound for large-payload flush / accumulate scenarios where
// write back-pressure + coroutine dispatch can legitimately push
// beyond IO_OP_TIMEOUT_MS on slow runners.
internal const val IO_OP_LONG_TIMEOUT_MS = 10_000L

// A fixed port nothing listens on, for connection-refused tests.
// Port 1 (tcpmux) is outside the ephemeral range and unassigned in
// practice, so a loopback connect to it is reliably refused. A freed
// ephemeral port must NOT be used: a loopback connect to one can win a
// TCP simultaneous-open against the connecting socket's own auto-bound
// local port and succeed (self-connect), flaking the test.
internal const val REFUSED_PORT = 1

private val udsSeq = java.util.concurrent.atomic.AtomicInteger(0)

internal fun uniqueUdsPath(): String {
    val pid = ProcessHandle.current().pid()
    val seq = udsSeq.getAndIncrement()
    return "/tmp/keel-nio-uds-$pid-$seq.sock"
}

// Budget for a closed / rolled-back listener's port to become claimable
// again. The JDK defers the kernel-level close of a selector-registered
// channel until the next selection operation, so release is prompt (the
// engine wakes the boss loop) but not synchronous with close().
internal const val PORT_RELEASE_BUDGET_MS = 2_000L
internal const val PORT_RELEASE_POLL_MS = 20L

/** Claims [port] with a raw ServerSocket, retrying up to [budgetMillis]. */
internal fun assertPortReleased(port: Int, budgetMillis: Long = PORT_RELEASE_BUDGET_MS) {
    val deadline = System.currentTimeMillis() + budgetMillis
    var last: Exception? = null
    while (System.currentTimeMillis() < deadline) {
        try {
            ServerSocket(port, 1, InetAddress.getLoopbackAddress()).close()
            return
        } catch (e: BindException) {
            last = e
            Thread.sleep(PORT_RELEASE_POLL_MS)
        }
    }
    fail("port $port still bound ${budgetMillis}ms after the listener was closed", last)
}

internal fun connectRawClient(port: Int): Socket {
    return Socket(InetAddress.getLoopbackAddress(), port).apply {
        soTimeout = 5000
    }
}

internal fun rawWrite(client: Socket, data: String) {
    client.getOutputStream().write(data.toByteArray())
    client.getOutputStream().flush()
}

internal fun rawRead(client: Socket, size: Int): String {
    val buf = ByteArray(size)
    var total = 0
    while (total < size) {
        val n = client.getInputStream().read(buf, total, size - total)
        if (n <= 0) break
        total += n
    }
    return String(buf, 0, total)
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
