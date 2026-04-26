package io.github.fukusaka.keel.engine.nio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.Socket
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
 * The same six-bucket split is documented in `.claude/rules/testing.md`
 * § "テストカテゴリ"; this file (and the categorised test files below it)
 * is the concrete realisation for the NIO engine.
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

private val udsSeq = java.util.concurrent.atomic.AtomicInteger(0)

internal fun uniqueUdsPath(): String {
    val pid = ProcessHandle.current().pid()
    val seq = udsSeq.getAndIncrement()
    return "/tmp/keel-nio-uds-$pid-$seq.sock"
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
