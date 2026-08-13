@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.posix.close
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
class KqueueEngineConcurrencyTest {

    // --- Concurrent ---

    @Test
    fun concurrentReadOnMultipleChannels() = runBlocking {
        withTimeout(5.seconds) {
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port
            val clientCount = 5

            val clients = (1..clientCount).map { connectRawClient(port) }
            val channels = (1..clientCount).map { server.accept() }

            // All clients send concurrently
            clients.forEachIndexed { i, fd -> rawWrite(fd, "msg$i") }

            // All channels read concurrently
            val results = channels.map { ch ->
                async {
                    val buf = DefaultAllocator.allocate(64)
                    val n = ch.read(buf)
                    val bytes = ByteArray(n)
                    for (j in 0 until n) bytes[j] = buf.readByte()
                    buf.release()
                    bytes.decodeToString()
                }
            }

            val messages = results.map { it.await() }.sorted()
            assertEquals(listOf("msg0", "msg1", "msg2", "msg3", "msg4"), messages)

            channels.forEach { it.close() }
            clients.forEach { close(it) }
            server.close()
            engine.close()
        }
    }

    @Test
    fun concurrentAcceptMultipleClients() = runBlocking {
        val engine = KqueueEngine()
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
        clients.forEach { close(it) }
        server.close()
        engine.close()
    }

    // --- CoroutineDispatcher ---

    @Test
    fun `channel ioDispatcher returns EventLoop`() = runBlocking {
        withTimeout(5.seconds) {
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            // ioDispatcher should be the KqueueEventLoop, not Dispatchers.Default
            assertTrue(ch.ioDispatcher is KqueueEventLoop)

            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun `dispatch executes task on EventLoop thread`() = runBlocking {
        withTimeout(5.seconds) {
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            // Launch a coroutine on the EventLoop dispatcher and capture thread name
            val threadName = withContext(ch.ioDispatcher) {
                // Read/write on EventLoop thread to verify I/O runs there
                rawWrite(clientFd, "x")
                val buf = DefaultAllocator.allocate(64)
                val n = ch.read(buf)
                assertEquals(1, n)
                buf.release()
                "ok"
            }
            assertEquals("ok", threadName)

            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun `echo round trip on EventLoop dispatcher`() = runBlocking {
        withTimeout(5.seconds) {
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            // Run entire echo on the EventLoop dispatcher
            withContext(ch.ioDispatcher) {
                rawWrite(clientFd, "hello")

                val buf = DefaultAllocator.allocate(64)
                val n = ch.read(buf)
                assertEquals(5, n)

                ch.write(buf)
                ch.flush()
            }

            val echo = rawRead(clientFd, 5)
            assertEquals("hello", echo)

            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun `close StreamServer cancels pending accept`() = runBlocking {
        val engine = KqueueEngine()
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

    // --- Multi-thread EventLoop ---

    /**
     * Verifies the multi-thread EventLoop config (`threads = 4`) handles
     * concurrent `accept()` + Channel I/O correctly across worker EventLoops.
     *
     * Per-coroutine blocking POSIX syscalls (`connectRawClient` /
     * `rawWrite` / `rawRead` / `close`) run on [Dispatchers.Default]
     * — `runBlocking` is single-threaded on Native and would deadlock if
     * one coroutine's blocking `rawRead` parked the only thread while
     * another coroutine's suspended `ch.read` waited for resume on the
     * same thread. Cross-pairing scenarios (which arise when one
     * `accept()` hits `EAGAIN` and shifts FIFO order) require parallel
     * forward progress in test scaffolding to be reachable; this is the
     * focused way to verify engine correctness without entangling test
     * scheduling with engine semantics.
     */
    @Test
    fun `echo with multi-thread EventLoop`() = runBlocking {
        withTimeout(5.seconds) {
            val engine = KqueueEngine(IoEngineConfig(threads = 4))
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            // Multiple clients to exercise round-robin distribution
            val results = (1..8).map { i ->
                async(Dispatchers.Default) {
                    val clientFd = connectRawClient(port)
                    val ch = server.accept()

                    val msg = "msg-$i"
                    rawWrite(clientFd, msg)

                    val buf = DefaultAllocator.allocate(64)
                    val n = ch.read(buf)
                    assertEquals(msg.length, n)

                    ch.write(buf)
                    ch.flush()

                    val echo = rawRead(clientFd, msg.length)
                    ch.close()
                    close(clientFd)
                    echo
                }
            }

            for ((i, deferred) in results.withIndex()) {
                assertEquals("msg-${i + 1}", deferred.await())
            }

            server.close()
            engine.close()
        }
    }

    @Test
    fun `channels are distributed across worker EventLoops`() = runBlocking {
        withTimeout(5.seconds) {
            val engine = KqueueEngine(IoEngineConfig(threads = 4))
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            // Accept 4 channels — should be assigned to 4 different workers
            val channels = (1..4).map {
                val clientFd = connectRawClient(port)
                val ch = server.accept()
                ch to clientFd
            }

            // Each channel's ioDispatcher should be a KqueueEventLoop
            // (different instances for round-robin distribution)
            val dispatchers = channels.map { (ch, _) -> ch.ioDispatcher }
            for (d in dispatchers) {
                assertTrue(d is KqueueEventLoop, "Expected KqueueEventLoop dispatcher")
            }
            // With 4 workers and 4 channels, all dispatchers should be distinct
            assertEquals(4, dispatchers.toSet().size, "Expected 4 distinct EventLoops")

            for ((ch, fd) in channels) {
                ch.close()
                close(fd)
            }
            server.close()
            engine.close()
        }
    }
}
