package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.ConnectConfig
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.SocketOptions
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end smoke tests for `ConnectConfig.socketOptions` and
 * `BindConfig.childSocketOptions` on the Node.js engine.
 *
 * Like NWConnection, Node.js `net.Socket` does not expose a getter for
 * TCP_NODELAY / SO_KEEPALIVE — the setters (`setNoDelay` /
 * `setKeepAlive`) are one-way configuration calls. These tests verify:
 *
 * 1. Passing `socketOptions` / `childSocketOptions` through the helper
 *    (`applySocketOptions`) does not crash the Node.js socket.
 * 2. End-to-end echo still works with options applied (proves the
 *    connection is functional after setNoDelay / setKeepAlive).
 * 3. `receiveBufferSize` / `sendBufferSize` are silently ignored
 *    (Node.js `net.Socket` has no corresponding API); the engine
 *    accepts the options without error.
 *
 * Strong verification (e.g., observing `TCP_NODELAY` packet timing)
 * would require packet capture and is outside unit-test scope.
 */
class NodeSocketOptionsTest {

    @Test
    fun bindChildSocketOptionsRoundTripsEcho() = runTest(timeout = 15.seconds) {
        val engine = NodeEngine()
        val server = engine.bind(
            InetSocketAddress("127.0.0.1", 0),
            BindConfig(
                childSocketOptions = SocketOptions(tcpNoDelay = true, keepAlive = true),
            ),
        )
        val port = (server.localAddress as InetSocketAddress).port

        val clientCh = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        val writeBuf = DefaultAllocator.allocate(64)
        for (b in "hello".encodeToByteArray()) writeBuf.writeByte(b)
        clientCh.write(writeBuf) // transfer
        clientCh.flush()

        val readBuf = DefaultAllocator.allocate(64)
        val n = serverCh.read(readBuf)
        assertEquals(5, n)

        readBuf.release()
        clientCh.close()
        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun connectConfigSocketOptionsRoundTripsEcho() = runTest(timeout = 15.seconds) {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientCh = engine.connect(
            InetSocketAddress("127.0.0.1", port),
            ConnectConfig(socketOptions = SocketOptions(tcpNoDelay = true, keepAlive = true)),
        )
        val serverCh = server.accept()

        val writeBuf = DefaultAllocator.allocate(64)
        for (b in "world".encodeToByteArray()) writeBuf.writeByte(b)
        clientCh.write(writeBuf) // transfer
        clientCh.flush()

        val readBuf = DefaultAllocator.allocate(64)
        val n = serverCh.read(readBuf)
        assertEquals(5, n)

        readBuf.release()
        clientCh.close()
        serverCh.close()
        server.close()
        engine.close()
    }

    @Test
    fun unsupportedBufferSizeOptionsAcceptedSilently() = runTest(timeout = 15.seconds) {
        // Node.js net.Socket exposes no SO_RCVBUF / SO_SNDBUF API.
        // receiveBufferSize / sendBufferSize must be accepted without
        // error; applySocketOptions only touches tcpNoDelay + keepAlive.
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientCh = engine.connect(
            InetSocketAddress("127.0.0.1", port),
            ConnectConfig(
                socketOptions = SocketOptions(
                    tcpNoDelay = true,
                    receiveBufferSize = 65536,
                    sendBufferSize = 131072,
                ),
            ),
        )
        clientCh.close()
        server.close()
        engine.close()
    }
}
