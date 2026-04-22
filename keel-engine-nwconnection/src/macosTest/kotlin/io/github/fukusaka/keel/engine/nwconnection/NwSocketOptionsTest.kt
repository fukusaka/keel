package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.ConnectConfig
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.native.posix.PosixRawClient
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end smoke tests for `ConnectConfig.socketOptions` and
 * `BindConfig.childSocketOptions` on the NWConnection engine.
 *
 * Unlike NIO / Netty engines, NW framework does not expose a getter
 * API for TCP_NODELAY / SO_KEEPALIVE on an `NWConnection` — the options
 * are applied internally by the framework's TCP configure block. These
 * tests verify:
 *
 * 1. Passing `socketOptions` / `childSocketOptions` through the C
 *    wrapper (`keel_nw_create_tcp_params_with_options`) does not crash.
 * 2. End-to-end echo still works with options applied (proves the
 *    connection is functional, not just created).
 * 3. `receiveBufferSize` / `sendBufferSize` are silently ignored
 *    (NW framework has no buffer-size API); the engine accepts the
 *    options without error.
 *
 * Strong verification (e.g., observing `TCP_NODELAY` packet timing)
 * would require packet capture and is outside unit-test scope.
 */
@OptIn(ExperimentalForeignApi::class)
class NwSocketOptionsTest {

    private val testTimeout = 10.seconds

    private fun runTest(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        runBlocking { withTimeout(testTimeout, block) }

    @Test
    fun `bind with childSocketOptions accepts and echoes`() = runTest {
        val engine = NwEngine()
        val server = engine.bind(
            InetSocketAddress("127.0.0.1", 0),
            BindConfig(
                childSocketOptions = SocketOptions(tcpNoDelay = true, keepAlive = true),
            ),
        )
        val port = (server.localAddress as InetSocketAddress).port
        val clientFd = PosixRawClient.rawConnect(port)
        try {
            val serverCh = server.accept()
            PosixRawClient.rawWrite(clientFd, "hello")
            val buf = DefaultAllocator.allocate(64)
            val n = serverCh.read(buf)
            assertEquals(5, n)
            serverCh.write(buf)
            serverCh.flush()
            assertEquals("hello", PosixRawClient.rawRead(clientFd, 5))
            buf.release()
            serverCh.close()
        } finally {
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun `connect with ConnectConfig tcpNoDelay plus keepAlive round-trips`() = runTest {
        val engine = NwEngine()
        // Use the engine itself for the server (no childSocketOptions) and a
        // second engine instance for the client with ConnectConfig.socketOptions.
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port
        try {
            val clientEngine = NwEngine()
            try {
                val client = clientEngine.connect(
                    InetSocketAddress("127.0.0.1", port),
                    ConnectConfig(
                        socketOptions = SocketOptions(tcpNoDelay = true, keepAlive = true),
                    ),
                )
                val serverCh = server.accept()

                val send = DefaultAllocator.allocate(5)
                "hello".encodeToByteArray().forEach { send.writeByte(it) }
                client.write(send)
                client.flush()
                send.release()

                val recv = DefaultAllocator.allocate(64)
                val n = serverCh.read(recv)
                assertEquals(5, n)
                recv.release()

                client.close()
                serverCh.close()
            } finally {
                clientEngine.close()
            }
        } finally {
            server.close()
            engine.close()
        }
    }

    @Test
    fun `ConnectConfig with unsupported buffer size options is accepted silently`() = runTest {
        // NW framework does not expose SO_RCVBUF / SO_SNDBUF. The helper
        // must accept non-null values without erroring — they are silently
        // ignored. Same opaque path as tcpNoDelay to prove the isEmpty
        // short-circuit isn't bypassed incorrectly.
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port
        try {
            val clientEngine = NwEngine()
            try {
                val client = clientEngine.connect(
                    InetSocketAddress("127.0.0.1", port),
                    ConnectConfig(
                        socketOptions = SocketOptions(
                            tcpNoDelay = true,
                            receiveBufferSize = 65536,
                            sendBufferSize = 131072,
                        ),
                    ),
                )
                client.close()
            } finally {
                clientEngine.close()
            }
        } finally {
            server.close()
            engine.close()
        }
    }
}
