package io.github.fukusaka.keel.engine.netty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.Socket
import kotlin.time.Duration.Companion.seconds

/**
 * Shared helpers + constants for the categorised `NettyEngine*Test` files.
 *
 * Test category split:
 *
 * | file | scope |
 * |---|---|
 * | [NettyEngineLifecycleTest] | engine create/close, server bind/close, error paths, double close, UDS binding |
 * | [NettyEngineReadWriteTest] | echo, multi-write, half-close, `asSuspendSource` / `asSuspendSink` |
 * | [NettyEngineConnectTest]   | client `connect()` flows |
 * | [NettyEngineConcurrencyTest] | concurrent reads, FIFO accept queue, close race, cancellation |
 * | [NettyEngineResourceTest]  | `TrackingAllocator` leak detection |
 *
 * Mirrors the same category split applied to the other engine test
 * suites (kqueue / epoll / io_uring / nio / netty / nwconnection /
 * nodejs). This file is the concrete realisation for the Netty engine.
 */

internal val TEST_TIMEOUT = 10.seconds

internal fun runTest(block: suspend CoroutineScope.() -> Unit) =
    runBlocking { withTimeout(TEST_TIMEOUT, block) }

internal const val IO_OP_TIMEOUT_MS = 5_000L
internal const val IO_OP_SHORT_TIMEOUT_MS = 3_000L

private val udsSeq = java.util.concurrent.atomic.AtomicInteger(0)

internal fun uniqueUdsPath(): String {
    val pid = ProcessHandle.current().pid()
    val seq = udsSeq.getAndIncrement()
    return "/tmp/keel-netty-uds-$pid-$seq.sock"
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
