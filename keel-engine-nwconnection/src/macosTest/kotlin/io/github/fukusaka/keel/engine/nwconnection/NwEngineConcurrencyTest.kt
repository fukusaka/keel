package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
class NwEngineConcurrencyTest {

    @Test
    fun concurrentReadOnMultipleChannels() = runBlocking {
        withTimeout(15.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
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

    /**
     * Regression for the pre-fix bug where multiple concurrent
     * `accept()` callers on `NwStreamServer` overwrote each other in
     * `pendingAcceptCont` (single-slot under `withLock` / pthread_mutex).
     * Pre-fix the 2nd+ accept that suspended with `pendingConnections`
     * empty silently overwrote the prior continuation; that waiter
     * never resumed. Fix queues every waiter in
     * `pendingAcceptConts: ArrayDeque<...>`.
     *
     * **Why this test does not echo bytes**: NWConnection's connection
     * lifecycle is asynchronous (`keel_nw_start_conn_async` plus an
     * internal state-machine that signals READY on the listener queue),
     * and under cross-pairing of POSIX raw clients with NWConnection
     * server channels the test would intermittently see early peer
     * close events from NWConnection's teardown ordering — an
     * NWConnection-specific issue unrelated to the
     * `pendingAcceptConts` queue. To keep the regression focus narrow,
     * this test only verifies that every concurrent `accept()` returns
     * a live channel (the multi-waiter chain semantic), then closes
     * everything without sending bytes.
     *
     * Each accept coroutine runs on [Dispatchers.Default] so that
     * `connectRawClient` (blocking POSIX) doesn't park `runBlocking`'s
     * single thread.
     */
    @Test
    fun multipleConcurrentAcceptsAreFifoQueued() = runBlocking {
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val acceptJobs = (1..8).map {
            async(Dispatchers.Default) { server.accept() }
        }
        val clientFds = (1..8).map {
            async(Dispatchers.Default) { connectRawClient(port) }
        }

        val channels = acceptJobs.map { withTimeout(IO_OP_TIMEOUT_MS) { it.await() } }
        val fds = clientFds.map { it.await() }

        assertEquals(8, channels.size)
        channels.forEach { assertTrue(it.isOpen) }

        channels.forEach { it.close() }
        fds.forEach { close(it) }
        server.close()
        engine.close()
    }

    @Test
    fun concurrentAcceptMultipleClients() = runBlocking {
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
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

    @Test
    fun `cancel read coroutine does not crash`() = runBlocking {
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
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
        close(clientFd)
        server.close()
        engine.close()
    }

    @Test
    fun `cancel write coroutine does not crash`() = runBlocking {
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        val writeJob = launch {
            // No release here, unlike the read test above: `write` takes ownership of
            // the buffer and the transport releases it after `flush` completes or on
            // teardown, so releasing it here would be a second release. The empty
            // `finally` this replaces recorded that decision only by being empty.
            val buf = DefaultAllocator.allocate(64)
            buf.writerIndex = 64
            ch.write(buf)
            // flush suspends on keel_nw_write_async callback
            ch.flush()
        }

        delay(100)
        writeJob.cancel()

        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { writeJob.join() }
        assertTrue(ch.isOpen)

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }
}
