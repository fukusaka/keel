package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.ConnectConfig
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.SocketOptions
import io.netty.channel.ChannelOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Verifies `ConnectConfig.socketOptions` (client-side) and
 * `BindConfig.childSocketOptions` (accepted-side) reach the underlying
 * Netty `Channel` via `Bootstrap.option` / `ServerBootstrap.childOption`.
 *
 * Mirrors `NioSocketOptionsTest` — drives real sockets through loopback
 * and reads back via `channel.config().getOption(ChannelOption.*)` on the
 * keel-side channel (accessible through the internal
 * `NettyIoTransport.nettyChannel`).
 */
class NettySocketOptionsTest {

    private val testTimeout = 10.seconds

    private fun runTest(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        runBlocking { withTimeout(testTimeout, block) }

    @Test
    fun `connect with ConnectConfig applies socket options to client Channel`() = runTest {
        withTimeout(15.seconds) {
            val engine = NettyEngine()
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
                val transport = (client as NettyPipelinedChannel).transport as NettyIoTransport
                val cfg = transport.nettyChannel.config()
                assertEquals(true, cfg.getOption(ChannelOption.TCP_NODELAY))
                assertEquals(true, cfg.getOption(ChannelOption.SO_KEEPALIVE))
                // Kernel may round up to rmem_default / sndbuf_default — accept any >= requested.
                assert((cfg.getOption(ChannelOption.SO_RCVBUF) as Int) >= 65536) {
                    "SO_RCVBUF ${cfg.getOption(ChannelOption.SO_RCVBUF)} < 65536"
                }
                assert((cfg.getOption(ChannelOption.SO_SNDBUF) as Int) >= 131072) {
                    "SO_SNDBUF ${cfg.getOption(ChannelOption.SO_SNDBUF)} < 131072"
                }
                client.close()
            } finally {
                server.close()
                engine.close()
            }
        }
    }

    @Test
    fun `bind with childSocketOptions applies to accepted Channel`() = runTest {
        withTimeout(15.seconds) {
            val engine = NettyEngine()
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
                val transport = (accepted as NettyPipelinedChannel).transport as NettyIoTransport
                val cfg = transport.nettyChannel.config()
                val tcpNoDelay = cfg.getOption(ChannelOption.TCP_NODELAY)
                val keepAlive = cfg.getOption(ChannelOption.SO_KEEPALIVE)
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
    }

    @Test
    fun `connect without ConnectConfig leaves kernel defaults`() = runTest {
        withTimeout(15.seconds) {
            val engine = NettyEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port
            try {
                val client = engine.connect(InetSocketAddress("127.0.0.1", port))
                val transport = (client as NettyPipelinedChannel).transport as NettyIoTransport
                // Kernel defaults vary; only assert the short-circuit path doesn't error.
                transport.nettyChannel.config().getOption(ChannelOption.TCP_NODELAY)
                client.close()
            } finally {
                server.close()
                engine.close()
            }
        }
    }

    @Test
    fun `connect with partial ConnectConfig skips null properties`() = runTest {
        withTimeout(15.seconds) {
            val engine = NettyEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port
            try {
                val client = engine.connect(
                    InetSocketAddress("127.0.0.1", port),
                    ConnectConfig(socketOptions = SocketOptions(tcpNoDelay = true)),
                )
                val transport = (client as NettyPipelinedChannel).transport as NettyIoTransport
                assertEquals(true, transport.nettyChannel.config().getOption(ChannelOption.TCP_NODELAY))
                client.close()
            } finally {
                server.close()
                engine.close()
            }
        }
    }
}
