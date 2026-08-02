package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NettyEngineConcurrencyTest {

    @Test
    fun concurrentReadOnMultipleChannels() = runTest {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port
        val clientCount = 5

        val clients = (1..clientCount).map { connectRawClient(port) }
        val channels = (1..clientCount).map { server.accept() }

        // All clients send concurrently
        clients.forEachIndexed { i, client -> rawWrite(client, "msg$i") }

        // All channels read concurrently
        val results = channels.map { ch ->
            async {
                val buf = DefaultAllocator.allocate(64)
                val n = ch.read(buf)
                val bytes = ByteArray(n)
                for (j in 0 until n) bytes[j] = buf.readByte()
                buf.release()
                String(bytes)
            }
        }

        val messages = results.map { it.await() }.sorted()
        assertEquals(listOf("msg0", "msg1", "msg2", "msg3", "msg4"), messages)

        channels.forEach { it.close() }
        clients.forEach { it.close() }
        server.close()
        engine.close()
    }

    /**
     * Regression for the pre-fix bug where multiple concurrent
     * `accept()` callers on a `NettyStreamServer` overwrote each other
     * in `pendingAcceptCont` (single-slot). Fix moves the field to
     * `pendingAcceptConts: ArrayDeque<...>` (PR #369), so each suspended
     * caller gets its own slot in FIFO order.
     *
     * Each per-coroutine pipeline runs on [Dispatchers.Default] so
     * blocking POSIX `Socket` scaffolding doesn't park `runBlocking`'s
     * single thread when accept FIFO order shifts under load.
     */
    @Test
    fun multipleConcurrentAcceptsAreFifoQueued() = runTest {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val results = (1..8).map { i ->
            async(Dispatchers.Default) {
                val client = connectRawClient(port)
                val ch = server.accept()

                val msg = "msg-$i"
                client.getOutputStream().write(msg.toByteArray())
                client.getOutputStream().flush()

                val buf = DefaultAllocator.allocate(64)
                val n = ch.read(buf)
                assertEquals(msg.length, n)

                ch.write(buf)
                ch.flush()

                val echoBytes = ByteArray(msg.length)
                var read = 0
                while (read < msg.length) {
                    val r = client.getInputStream().read(echoBytes, read, msg.length - read)
                    if (r < 0) break
                    read += r
                }
                ch.close()
                client.close()
                String(echoBytes, 0, read)
            }
        }
        for ((i, deferred) in results.withIndex()) {
            assertEquals("msg-${i + 1}", deferred.await())
        }

        server.close()
        engine.close()
    }

    @Test
    fun concurrentAcceptMultipleClients() = runTest {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port
        val clientCount = 10

        // Accept all concurrently
        val acceptJob = async {
            (1..clientCount).map { server.accept() }
        }

        // Connect all clients
        val clients = (1..clientCount).map { connectRawClient(port) }

        val channels = withTimeout(IO_OP_TIMEOUT_MS) { acceptJob.await() }
        assertEquals(clientCount, channels.size)
        channels.forEach { assertTrue(it.isOpen) }

        channels.forEach { it.close() }
        clients.forEach { it.close() }
        server.close()
        engine.close()
    }

    @Test
    fun closeChannelWhileReadIsSuspended() = runTest {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        // Start a read that will suspend (no data sent)
        val readResult = async {
            val buf = DefaultAllocator.allocate(64)
            try {
                ch.read(buf)
            } finally {
                buf.release()
            }
        }

        // Give the read time to suspend
        delay(100)

        // Close the channel while read is suspended
        ch.close()

        // Read should return -1 (EOF) without hanging
        val n = withTimeout(IO_OP_SHORT_TIMEOUT_MS) { readResult.await() }
        assertEquals(-1, n)

        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun closeServerChannelWhileAcceptIsSuspended() = runTest {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)

        // Start accept that will suspend (no client connects)
        val acceptResult = async {
            try {
                server.accept()
                "accepted"
            } catch (_: CancellationException) {
                "cancelled"
            }
        }

        // Give accept time to suspend
        delay(100)

        // Close the server while accept is suspended
        server.close()

        // Accept should be cancelled without hanging
        val result = withTimeout(IO_OP_SHORT_TIMEOUT_MS) { acceptResult.await() }
        assertEquals("cancelled", result)

        engine.close()
    }

    @Test
    fun clientDisconnectDuringRead() = runTest {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        // Start a read that will suspend
        val readResult = async {
            val buf = DefaultAllocator.allocate(64)
            try {
                ch.read(buf)
            } finally {
                buf.release()
            }
        }

        // Give read time to suspend
        delay(100)

        // Client disconnects — triggers channelInactive on Netty EventLoop
        client.close()

        // Read should return -1 from channelInactive resume
        val n = withTimeout(IO_OP_SHORT_TIMEOUT_MS) { readResult.await() }
        assertEquals(-1, n)

        ch.close()
        server.close()
        engine.close()
    }

    @Test
    fun cancelReadCoroutine() = runTest {
        val engine = NettyEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        // Start a read that will suspend
        val readJob = launch {
            val buf = DefaultAllocator.allocate(64)
            try {
                ch.read(buf)
            } finally {
                buf.release()
            }
        }

        // Give read time to suspend
        delay(100)

        // Cancel the coroutine
        readJob.cancel()

        // Should complete without hanging
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { readJob.join() }

        // Channel should still be usable after cancellation
        assertTrue(ch.isOpen)

        ch.close()
        client.close()
        server.close()
        engine.close()
    }
}
