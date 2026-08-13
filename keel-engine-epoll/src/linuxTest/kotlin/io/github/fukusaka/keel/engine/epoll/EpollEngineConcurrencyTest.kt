@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
class EpollEngineConcurrencyTest {

    // --- Concurrent ---

    @Test
    fun concurrentReadOnMultipleChannels() = runBlocking {
        withTimeout(5.seconds) {
            val engine = EpollEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port
            val clientCount = 5

            val clients = (1..clientCount).map { connectRawClient(port) }
            val channels = (1..clientCount).map { server.accept() }

            clients.forEachIndexed { i, fd -> rawWrite(fd, "msg$i") }

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
        val engine = EpollEngine()
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
            val engine = EpollEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            // ioDispatcher should be the EpollEventLoop, not Dispatchers.Default
            assertTrue(ch.ioDispatcher is EpollEventLoop)

            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun `dispatch executes task on EventLoop thread`() = runBlocking {
        withTimeout(5.seconds) {
            val engine = EpollEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            // Launch a coroutine on the EventLoop dispatcher and verify I/O works
            val result = withContext(ch.ioDispatcher) {
                rawWrite(clientFd, "x")
                val buf = DefaultAllocator.allocate(64)
                val n = ch.read(buf)
                assertEquals(1, n)
                buf.release()
                "ok"
            }
            assertEquals("ok", result)

            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun `echo round trip on EventLoop dispatcher`() = runBlocking {
        withTimeout(5.seconds) {
            val engine = EpollEngine()
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
    fun `multiple dispatches are executed in FIFO order`() = runBlocking {
        withTimeout(5.seconds) {
            val engine = EpollEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            // Dispatch multiple tasks and verify they execute in order
            val results = mutableListOf<Int>()
            withContext(ch.ioDispatcher) {
                // All dispatches go to the same EventLoop thread's taskQueue
                launch(ch.ioDispatcher) { results.add(1) }
                launch(ch.ioDispatcher) { results.add(2) }
                launch(ch.ioDispatcher) { results.add(3) }
            }

            // drainTasks processes the taskQueue in FIFO order
            assertEquals(listOf(1, 2, 3), results)

            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun `dispatch from within EventLoop thread`() = runBlocking {
        withTimeout(5.seconds) {
            val engine = EpollEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            // Test that dispatching from within a dispatched task works correctly.
            // This exercises the drainTasks() while loop: the inner dispatch
            // enqueues a new task that must be drained in the same iteration.
            val result = withContext(ch.ioDispatcher) {
                withContext(ch.ioDispatcher) {
                    "nested"
                }
            }
            assertEquals("nested", result)

            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun `concurrent dispatch from multiple coroutines`() = runBlocking {
        withTimeout(5.seconds) {
            val engine = EpollEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            // Launch multiple coroutines that all dispatch to the EventLoop
            // concurrently, exercising the taskMutex thread safety
            val counter = kotlin.concurrent.AtomicInt(0)
            val jobs = (1..10).map {
                async {
                    withContext(ch.ioDispatcher) {
                        counter.incrementAndGet()
                    }
                }
            }
            jobs.forEach { it.await() }
            assertEquals(10, counter.value)

            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
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
            val engine = EpollEngine(IoEngineConfig(threads = 4))
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
            val engine = EpollEngine(IoEngineConfig(threads = 4))
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            // Accept 4 channels — should be assigned to 4 different workers
            val channels = (1..4).map {
                val clientFd = connectRawClient(port)
                val ch = server.accept()
                ch to clientFd
            }

            // Each channel's ioDispatcher should be an EpollEventLoop
            // (different instances for round-robin distribution)
            val dispatchers = channels.map { (ch, _) -> ch.ioDispatcher }
            for (d in dispatchers) {
                assertTrue(d is EpollEventLoop, "Expected EpollEventLoop dispatcher")
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
