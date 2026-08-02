package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NioEngineConcurrencyTest {

    @Test
    fun concurrentReadOnMultipleChannels() = runTest {
        val engine = NioEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port
        val clientCount = 5

        val clients = (1..clientCount).map { connectRawClient(port) }
        val channels = (1..clientCount).map { server.accept() }

        clients.forEachIndexed { i, client -> rawWrite(client, "msg$i") }

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
     * `accept()` callers on `NioStreamServer` overwrote each other in
     * BOTH `pendingAcceptCont` (single-slot) AND the SelectionKey's
     * attached Runnable (each waiter set its own
     * `Runnable { cont.resume(...) }` via `key.attach`, with each new
     * call replacing the prior). PR #372 replaced the field with
     * `pendingAcceptConts: ArrayDeque<...>` and a single shared
     * `resumeAllRunnable` that resumes every queued waiter on
     * OP_ACCEPT fire.
     *
     * Each per-coroutine pipeline runs on [Dispatchers.Default] so
     * blocking JVM `Socket` scaffolding doesn't park `runBlocking`'s
     * single thread when accept FIFO order shifts under load.
     */
    @Test
    fun multipleConcurrentAcceptsAreFifoQueued() = runTest {
        val engine = NioEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
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
        val engine = NioEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port
        val clientCount = 10

        val acceptJob = async {
            (1..clientCount).map { server.accept() }
        }

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
    fun clientDisconnectDuringRead() = runTest {
        val engine = NioEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        val readResult = async {
            val buf = DefaultAllocator.allocate(64)
            try {
                ch.read(buf)
            } finally {
                buf.release()
            }
        }

        delay(100)
        client.close()

        val n = withTimeout(IO_OP_SHORT_TIMEOUT_MS) { readResult.await() }
        assertEquals(-1, n)

        ch.close()
        server.close()
        engine.close()
    }

    @Test
    fun cancelReadCoroutine() = runTest {
        val engine = NioEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val ch = server.accept()

        val readJob = launch {
            val buf = DefaultAllocator.allocate(64)
            try {
                ch.read(buf)
            } finally {
                buf.release()
            }
        }

        delay(100)
        readJob.cancel()

        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { readJob.join() }
        assertTrue(ch.isOpen)

        ch.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun `close StreamServer cancels pending accept`() = runTest {
        val engine = NioEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)

        val acceptJob = launch {
            server.accept()
        }

        delay(100)
        server.close()

        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { acceptJob.join() }
        assertTrue(acceptJob.isCancelled)

        engine.close()
    }
}
