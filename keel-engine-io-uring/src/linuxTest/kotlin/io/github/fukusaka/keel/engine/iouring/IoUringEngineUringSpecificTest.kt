@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io_uring.io_uring_prep_read
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.posix.close
import platform.posix.pipe
import platform.posix.write
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
class IoUringEngineUringSpecificTest {

    /**
     * Regression test for the submitWakeupSqe silent-drop deadlock.
     *
     * With ringSize=4: if (ringSize-1)=3 continuations are resumed during a
     * single CQE drain and each submits a new SQE (fast path), the subsequent
     * submitWakeupSqe() call finds the SQ ring full and must defer. Without the
     * wakeupSqePending retry, the next external dispatch() cannot wake the
     * EventLoop because no wakeup SQE is in-flight, causing deadlock.
     *
     * The test fills the ring with 3 blocking pipe reads, dispatches from an
     * external thread, then unblocks one pipe to let the EventLoop wake and
     * process the dispatch. A 2 s timeout detects a deadlock regression.
     */
    @Test
    fun `dispatch from external thread not lost when wakeup SQE was dropped due to full ring`() {
        val loop = IoUringEventLoop(IoEngineConfig().loggerFactory.logger("test"), ringSize = 4)
        loop.start()

        val n = 3
        val readFds = IntArray(n)
        val writeFds = IntArray(n)
        for (i in 0 until n) {
            IntArray(2).also { fds ->
                fds.usePinned { pipe(it.addressOf(0).reinterpret()) }
                readFds[i] = fds[0]
                writeFds[i] = fds[1]
            }
        }

        // 1-byte buffers for the pipe reads (IoBuf; released after loop.close).
        val readBufs = Array(n) { DefaultAllocator.allocate(1) }

        runBlocking {
            // Fill the SQ ring: submit n=3 blocking reads on the EventLoop.
            // With ringSize=4 (1 wakeup + 3 reads = full), the next
            // submitWakeupSqe() call during CQE processing will fail.
            val readJobs = (0 until n).map { i ->
                launch(loop) {
                    loop.submitAndAwait { sqe ->
                        io_uring_prep_read(sqe, readFds[i], readBufs[i].unsafePointer, 1u, 0u)
                    }
                }
            }

            // Dispatch from an external thread while the ring is under pressure.
            // If the wakeup SQE was dropped and wakeupSqePending is not retried,
            // this dispatch will never be processed → timeout = deadlock regression.
            val dispatched = CompletableDeferred<Unit>()
            launch(Dispatchers.Default) {
                loop.dispatch(EmptyCoroutineContext, Runnable { dispatched.complete(Unit) })
                // Unblock one pipe read to allow the EventLoop to escape
                // io_uring_submit_and_wait and process the queued dispatch.
                ByteArray(1) { 0x42 }.usePinned { write(writeFds[0], it.addressOf(0), 1uL) }
            }

            withTimeout(DISPATCH_AWAIT_TIMEOUT_MS) { dispatched.await() }

            // Unblock remaining reads and wait for all jobs to finish.
            ByteArray(1) { 0x42 }.usePinned { pinned ->
                for (i in 1 until n) write(writeFds[i], pinned.addressOf(0), 1uL)
            }
            readJobs.joinAll()
        }

        for (i in 0 until n) {
            close(readFds[i])
            close(writeFds[i])
        }
        for (buf in readBufs) buf.release()
        loop.close()
    }

    /**
     * Regression test for IORING_OP_ASYNC_CANCEL support.
     *
     * Cancelling a coroutine blocked in submitAndAwait() must:
     * 1. Submit IORING_OP_ASYNC_CANCEL targeting the in-flight SQE.
     * 2. Release the continuation slot (no slot leak).
     * 3. Leave the EventLoop in a functional state for subsequent operations.
     *
     * The test blocks a submitAndAwait() on a pipe read with no data, cancels
     * the coroutine, then verifies EventLoop functionality with a read on a
     * **separate** pipe.
     *
     * The verification pipe must be independent of the cancelled read's pipe:
     * ASYNC_CANCEL is asynchronous, so when `cancelAndJoin()` returns the
     * original read SQE may still be live in the kernel. A byte written to
     * the shared pipe would then race between the doomed original read and
     * the verification read — and if the cancelled read won the byte, the
     * verification read would starve and time out (the historical flake).
     * A dedicated pipe removes the race entirely.
     */
    @Test
    fun `cancelled submitAndAwait submits ASYNC_CANCEL and leaves EventLoop functional`() {
        val loop = IoUringEventLoop(IoEngineConfig().loggerFactory.logger("test"))
        loop.start()

        // Pipe 1: the read that will be cancelled — never fed any data.
        val fds = IntArray(2)
        fds.usePinned { pipe(it.addressOf(0).reinterpret()) }
        val readFd = fds[0]
        val writeFd = fds[1]
        // Pipe 2: an independent pipe for the post-cancel verification read,
        // so it never contends with the possibly-still-live cancelled read.
        val verifyFds = IntArray(2)
        verifyFds.usePinned { pipe(it.addressOf(0).reinterpret()) }
        val verifyReadFd = verifyFds[0]
        val verifyWriteFd = verifyFds[1]
        val buf = DefaultAllocator.allocate(1)
        val buf2 = DefaultAllocator.allocate(1)

        runBlocking {
            // Launch a coroutine on the EventLoop that blocks on a pipe read.
            val job = launch(loop) {
                loop.submitAndAwait { sqe ->
                    io_uring_prep_read(sqe, readFd, buf.unsafePointer, 1u, 0u)
                }
            }

            // Yield to the EventLoop so it submits the SQE to the kernel
            // before we cancel. withContext(loop) dispatches a no-op and
            // returns only after the EventLoop has processed it (and thus
            // the prior iteration's io_uring_submit_and_wait has run).
            withContext(loop) { /* yield */ }
            delay(50) // brief pause for io_uring_submit_and_wait to commit the SQE

            // Cancel the job; ASYNC_CANCEL is dispatched to the EventLoop.
            job.cancelAndJoin()

            // EventLoop must still be functional: a fresh read on the
            // independent pipe, with no contention from the cancelled read.
            ByteArray(1) { 0x42 }.usePinned { write(verifyWriteFd, it.addressOf(0), 1uL) }
            val n = withTimeout(DISPATCH_AWAIT_TIMEOUT_MS) {
                withContext(loop) {
                    loop.submitAndAwait { sqe ->
                        io_uring_prep_read(sqe, verifyReadFd, buf2.unsafePointer, 1u, 0u)
                    }
                }
            }
            assertEquals(1, n)
        }

        close(readFd)
        close(writeFd)
        close(verifyReadFd)
        close(verifyWriteFd)
        buf.release()
        buf2.release()
        loop.close()
    }

    @Test
    fun `multishot accept delivers multiple connections`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFds = IntArray(5) { connectRawClient(port) }

        val channels = (0 until 5).map {
            withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }
        }

        assertEquals(5, channels.size)
        channels.forEach { ch ->
            assertTrue(ch.isActive)
            ch.close()
        }

        clientFds.forEach { close(it) }
        server.close()
        engine.close()
    }

    /**
     * Verifies multishot accept correctly handles concurrent `accept()`
     * callers across worker EventLoops. Multiple coroutines suspend on
     * the bossLoop's pending-accept queue (FIFO chain) and each gets a
     * distinct connection delivered by a CQE.
     *
     * Pre-fix the single-slot `pendingAcceptCont` design overwrote
     * sibling waiters when two `accept()` calls reached the bossLoop
     * dispatch handler with `pendingFds` empty — the dropped continuation
     * never resumed and the corresponding `accept()` hung. Counterpart of
     * the POSIX engines' `echo with multi-thread EventLoop` test (PR #367).
     *
     * Per-coroutine blocking POSIX syscalls run on [Dispatchers.Default]
     * so they don't deadlock with `runBlocking`'s single thread under
     * cross-pairing.
     */
    @Test
    fun `multishot accept handles concurrent accept callers`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = IoUringEngine(IoEngineConfig(threads = 4))
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

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
    fun `multishot accept echo works for each connection`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        repeat(3) { i ->
            val clientFd = connectRawClient(port)
            val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

            val msg = "msg$i"
            rawWrite(clientFd, msg)

            val buf = DefaultAllocator.allocate(64)
            val n = withTimeout(IO_OP_TIMEOUT_MS) { ch.read(buf) }
            assertEquals(msg.length, n)

            ch.write(buf)
            ch.flush()

            val echo = rawRead(clientFd, msg.length)
            assertEquals(msg, echo)

            ch.close()
            close(clientFd)
        }

        server.close()
        engine.close()
    }

    @Test
    fun `close server channel while multishot armed`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        // Accept one connection to arm the multishot SQE.
        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }
        ch.close()
        close(clientFd)

        // Close while multishot is armed — must not throw or leak.
        server.close()
        assertFalse(server.isActive)

        engine.close()
    }

    @Test
    fun `accepted channels are assigned to worker EventLoops in round-robin order`() = runBlocking {
        val engine = IoUringEngine(IoEngineConfig(threads = 2))
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        // Accept 4 connections: should cycle through 2 workers
        val clientFds = IntArray(4) { connectRawClient(port) }
        val channels = (0 until 4).map { withTimeout(IO_OP_TIMEOUT_MS) { server.accept() } }

        // channel[0] and channel[2] should share the same dispatcher (worker 0)
        // channel[1] and channel[3] should share the same dispatcher (worker 1)
        assertEquals(channels[0].ioDispatcher, channels[2].ioDispatcher)
        assertEquals(channels[1].ioDispatcher, channels[3].ioDispatcher)
        assertFalse(channels[0].ioDispatcher == channels[1].ioDispatcher)

        channels.forEach { it.close() }
        clientFds.forEach { close(it) }
        server.close()
        engine.close()
    }

    @Test
    fun `close channel while multishot recv armed`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = withTimeout(IO_OP_TIMEOUT_MS) { server.accept() }

        // Read once to arm the multishot recv SQE.
        rawWrite(clientFd, "data")
        val source = ch.asSuspendSource()
        val buf = DefaultAllocator.allocate(64)
        withTimeout(IO_OP_TIMEOUT_MS) { source.read(buf) }
        buf.release()

        // Close while multishot recv is armed — must not leak slots or crash.
        source.close()
        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }
}
