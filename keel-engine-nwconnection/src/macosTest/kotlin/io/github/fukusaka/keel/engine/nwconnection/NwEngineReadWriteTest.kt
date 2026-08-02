package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.native.posix.PosixRawClient
import io.github.fukusaka.keel.native.posix.ReadResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.posix.close
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
class NwEngineReadWriteTest {

    @Test
    fun echoRoundTrip() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val serverCh = server.accept()

            // Client sends "hello"
            rawWrite(clientFd, "hello")

            // Server reads
            val readBuf = DefaultAllocator.allocate(64)
            val n = serverCh.read(readBuf)
            assertEquals(5, n)

            // Server echoes back
            serverCh.write(readBuf)
            serverCh.flush()

            // Client receives
            val echo = rawRead(clientFd, 5)
            assertEquals("hello", echo)

            serverCh.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun readReturnsMinusOneOnEof() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            close(clientFd) // Client closes -> EOF

            val buf = DefaultAllocator.allocate(64)
            val n = ch.read(buf)
            assertEquals(-1, n)

            buf.release()
            ch.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun writeAndFlush() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            val buf = DefaultAllocator.allocate(8)
            buf.writeByte(0x41) // 'A'
            buf.writeByte(0x42) // 'B'

            val written = ch.write(buf)
            assertEquals(2, written)

            ch.flush()

            val received = rawRead(clientFd, 2)
            assertEquals("AB", received)

            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun multipleWritesSingleFlush() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            val buf1 = DefaultAllocator.allocate(4)
            buf1.writeByte(0x41) // 'A'
            buf1.writeByte(0x42) // 'B'

            val buf2 = DefaultAllocator.allocate(4)
            buf2.writeByte(0x43) // 'C'
            buf2.writeByte(0x44) // 'D'

            ch.write(buf1)
            ch.write(buf2)
            ch.flush()

            val received = rawRead(clientFd, 4)
            assertEquals("ABCD", received)

            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun readAdvancesIoBufWriterIndex() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            rawWrite(clientFd, "abc")

            val buf = DefaultAllocator.allocate(64)
            assertEquals(0, buf.writerIndex)
            ch.read(buf)
            assertEquals(3, buf.writerIndex)
            assertEquals(3, buf.readableBytes)

            buf.release()
            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun writeTransfersOwnershipWithoutAdvancingIndex() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            val buf = DefaultAllocator.allocate(8)
            buf.writeByte(0x41)
            buf.writeByte(0x42)

            val observer = buf.retain()
            ch.write(buf) // transfer
            assertEquals(0, observer.readerIndex) // not advanced
            assertEquals(2, observer.writerIndex)

            ch.flush()
            observer.release()

            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun shutdownOutputSendsFin() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            ch.shutdownOutput()

            // Client should see EOF
            assertEquals(ReadResult.Eof, PosixRawClient.rawReadOnce(clientFd, 1))

            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun readAfterShutdownOutputStillWorks() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            ch.shutdownOutput()

            // Client can still send data
            rawWrite(clientFd, "hi")

            val buf = DefaultAllocator.allocate(64)
            val n = ch.read(buf)
            assertEquals(2, n)
            assertEquals('h'.code.toByte(), buf.readByte())
            assertEquals('i'.code.toByte(), buf.readByte())

            buf.release()
            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun asSuspendSourceReadsData() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            rawWrite(clientFd, "test")

            val source = io.github.fukusaka.keel.io.BufferedSuspendSource(
                ch.asSuspendSource(),
                ch.allocator,
            )
            val data = source.readByteArray(4)
            assertEquals("test", data.decodeToString())

            source.close()
            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun asSuspendSinkWritesData() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            val sink = io.github.fukusaka.keel.io.BufferedSuspendSink(
                ch.asSuspendSink(),
                ch.allocator,
            )
            sink.writeString("data")
            sink.flush()

            val received = rawRead(clientFd, 4)
            assertEquals("data", received)

            sink.close()
            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun asSuspendSourceEofReturnsMinusOne() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            close(clientFd)

            val buf = DefaultAllocator.allocate(64)
            val n = ch.asSuspendSource().read(buf)
            assertEquals(-1, n)

            buf.release()
            ch.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun requestFlushAwaitFlushCompleteBeforeCloseDeliversPendingWrite() = runBlocking {
        withTimeout(5.seconds) {
            // Regression: awaitPendingFlush() was a no-op in NwIoTransport.
            // When requestFlush() ran inline on connQueue followed by awaitFlushComplete()
            // (a no-op before fix) and then close(), nw_connection_cancel() fired before
            // the NWConnection write callback, cancelling in-flight sends and delivering
            // EOF to the client (~85% loss in the 1-VU close-per-request k6 bench).
            //
            // The HTTP server (respondFromBytes / respondNoContent) now calls
            // awaitFlushComplete() after requestFlush() inside withContext(ioDispatcher).
            // The fix: awaitPendingFlush() suspends (releasing connQueue) until the
            // write callback fires, so close() is only called after the write is
            // confirmed delivered to the network layer.
            repeat(10) { iteration ->
                val engine = NwEngine()
                val server = engine.bind("127.0.0.1", 0)
                val port = (server.localAddress as InetSocketAddress).port
                val clientFd = connectRawClient(port)
                val ch = server.accept()

                val payload = "hello"
                val buf = DefaultAllocator.allocate(payload.length)
                for (b in payload.encodeToByteArray()) buf.writeByte(b)

                ch.write(buf)
                // Simulate respondFromBytes()+close(): requestFlush() and
                // awaitFlushComplete() run on connQueue, then close() follows.
                // Before fix: awaitFlushComplete() returns immediately (no-op) and
                // close() dispatches teardown that races with the write callback.
                // After fix: awaitFlushComplete() suspends, callback fires, resumes;
                // close() only runs after the write is confirmed.
                withContext(ch.ioDispatcher) {
                    ch.requestFlush()
                    ch.awaitFlushComplete()
                }
                ch.close()

                val received = PosixRawClient.rawReadUpTo(clientFd, payload.length)
                assertEquals(
                    payload,
                    received,
                    "iteration $iteration: data lost on requestFlush+awaitFlushComplete+close",
                )

                close(clientFd)
                server.close()
                engine.close()
            }
        }
    }

    @Test
    fun streamingMultiChunkFlushesWithCloseDeliversAll() = runBlocking {
        withTimeout(5.seconds) {
            // Regression: responseChannel() streaming path did not call awaitFlushComplete()
            // in the finally block after the last requestFlush(). Multiple chunks are each
            // flushed inline on connQueue; after the final flush the coroutine completes,
            // join() resumes, and close() dispatches nw_connection_cancel() — which can
            // race with the in-flight nw_connection_send for the last chunk and drop it.
            //
            // The fix: responseChannel() now calls awaitFlushComplete() in the finally block
            // so that close() is only called after the write callback confirms delivery.
            // This test validates the multi-chunk streaming + close pattern at the transport
            // layer that responseChannel() relies on.
            repeat(10) { iteration ->
                val engine = NwEngine()
                val server = engine.bind("127.0.0.1", 0)
                val port = (server.localAddress as InetSocketAddress).port
                val clientFd = connectRawClient(port)
                val ch = server.accept()

                val chunks = listOf("chunk1", "chunk2", "final")
                val expected = chunks.joinToString("")

                // Simulate responseChannel() streaming: each chunk is written and flushed
                // separately on connQueue. awaitFlushComplete() after each flush confirms
                // delivery before the next write, matching the natural suspension from
                // bodyChannel.readAvailable() in the production loop. The finally-block
                // flush (last chunk here) must also await before close() is called.
                withContext(ch.ioDispatcher) {
                    for (chunk in chunks) {
                        val buf = DefaultAllocator.allocate(chunk.length)
                        for (b in chunk.encodeToByteArray()) buf.writeByte(b)
                        ch.write(buf)
                        ch.requestFlush()
                        ch.awaitFlushComplete()
                    }
                }
                ch.close()

                val received = PosixRawClient.rawReadUpTo(clientFd, expected.length)
                assertEquals(expected, received, "iteration $iteration: data lost in multi-chunk streaming flush+close")

                close(clientFd)
                server.close()
                engine.close()
            }
        }
    }

    @Test
    fun echoRoundTripWithFlushCoalescingDisabled() = runBlocking {
        // Verifies that IoEngineConfig.flushCoalescing = false preserves
        // correctness — every flush issues its own nw_connection_send
        // immediately instead of coalescing with the in-flight one.
        withTimeout(5.seconds) {
            val engine = NwEngine(IoEngineConfig(flushCoalescing = false))
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val serverCh = server.accept()

            rawWrite(clientFd, "hello")

            val readBuf = DefaultAllocator.allocate(64)
            val n = serverCh.read(readBuf)
            assertEquals(5, n)

            serverCh.write(readBuf)
            serverCh.flush()

            val echo = PosixRawClient.rawReadUpTo(clientFd, 5)
            assertEquals("hello", echo)

            withContext(serverCh.ioDispatcher) {
                serverCh.close()
            }
            close(clientFd)
            server.close()
            engine.close()
        }
    }
}
