package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.ConnectConfig
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.SocketOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.StandardSocketOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies `ConnectConfig.socketOptions` (client-side) and
 * `BindConfig.childSocketOptions` (accepted-side) reach the underlying
 * `java.nio.channels.SocketChannel` via `setOption(StandardSocketOptions.*)`.
 *
 * Mirrors Native engines' `FakeNativeSocketOps.appliedOptions` tests,
 * but drives real sockets through loopback and reads back via
 * `SocketChannel.getOption` on the keel-side channel (accessible through
 * the internal `NioIoTransport.socketChannel`).
 */
class NioSocketOptionsTest {

    private val testTimeout = 10.seconds

    private fun runTest(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        runBlocking { withTimeout(testTimeout, block) }

    @Test
    fun `connect with ConnectConfig applies socket options to client SocketChannel`() = runTest {
        val engine = NioEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port
        try {
            val client = engine.connect(
                InetSocketAddress("127.0.0.1", port),
                ConnectConfig(
                    socketOptions = SocketOptions(
                        tcpNoDelay = true,
                        keepAlive = true,
                        receiveBufferSize = 65536,
                        sendBufferSize = 131072,
                    ),
                ),
            )
            val transport = (client as NioPipelinedChannel).transport as NioIoTransport
            val sc = transport.socketChannel
            assertEquals(true, sc.getOption(StandardSocketOptions.TCP_NODELAY))
            assertEquals(true, sc.getOption(StandardSocketOptions.SO_KEEPALIVE))
            // Kernel may round up to rmem_default / sndbuf_default — accept any >= requested.
            assert(sc.getOption(StandardSocketOptions.SO_RCVBUF) >= 65536) {
                "SO_RCVBUF ${sc.getOption(StandardSocketOptions.SO_RCVBUF)} < 65536"
            }
            assert(sc.getOption(StandardSocketOptions.SO_SNDBUF) >= 131072) {
                "SO_SNDBUF ${sc.getOption(StandardSocketOptions.SO_SNDBUF)} < 131072"
            }
            client.close()
        } finally {
            server.close()
            engine.close()
        }
    }

    @Test
    fun `bind with childSocketOptions applies to accepted SocketChannel`() = runTest {
        val engine = NioEngine()
        val server = engine.bind(
            InetSocketAddress("127.0.0.1", 0),
            BindConfig(
                childSocketOptions = SocketOptions(
                    tcpNoDelay = true,
                    keepAlive = true,
                ),
            ),
        )
        val port = (server.localAddress as InetSocketAddress).port
        val acceptJob = async {
            val accepted = server.accept()
            val transport = (accepted as NioPipelinedChannel).transport as NioIoTransport
            val sc = transport.socketChannel
            val tcpNoDelay = sc.getOption(StandardSocketOptions.TCP_NODELAY)
            val keepAlive = sc.getOption(StandardSocketOptions.SO_KEEPALIVE)
            accepted.close()
            tcpNoDelay to keepAlive
        }
        try {
            val client = engine.connect(InetSocketAddress("127.0.0.1", port))
            val (tcpNoDelay, keepAlive) = acceptJob.await()
            assertEquals(true, tcpNoDelay)
            assertEquals(true, keepAlive)
            client.close()
        } finally {
            server.close()
            engine.close()
        }
    }

    @Test
    fun `connect without ConnectConfig leaves kernel defaults`() = runTest {
        val engine = NioEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port
        try {
            val client = engine.connect(InetSocketAddress("127.0.0.1", port))
            val transport = (client as NioPipelinedChannel).transport as NioIoTransport
            // Kernel defaults vary by OS — only assert we didn't crash / options
            // are readable. The earlier test proves non-default values take effect;
            // this one just asserts the short-circuit path doesn't error.
            transport.socketChannel.getOption(StandardSocketOptions.TCP_NODELAY)
            client.close()
        } finally {
            server.close()
            engine.close()
        }
    }

    @Test
    fun `connect with partial ConnectConfig skips null properties`() = runTest {
        val engine = NioEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port
        try {
            val client = engine.connect(
                InetSocketAddress("127.0.0.1", port),
                ConnectConfig(socketOptions = SocketOptions(tcpNoDelay = true)),
            )
            val transport = (client as NioPipelinedChannel).transport as NioIoTransport
            assertEquals(true, transport.socketChannel.getOption(StandardSocketOptions.TCP_NODELAY))
            // Other properties were null — kernel defaults, not asserted.
            client.close()
        } finally {
            server.close()
            engine.close()
        }
    }
}
