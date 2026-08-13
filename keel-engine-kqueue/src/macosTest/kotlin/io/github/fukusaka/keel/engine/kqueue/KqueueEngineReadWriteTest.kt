@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.native.posix.PosixRawClient
import io.github.fukusaka.keel.native.posix.ReadResult
import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
class KqueueEngineReadWriteTest {

    // --- read/write ---

    @Test
    fun echoRoundTrip() = runBlocking {
        withTimeout(5.seconds) {
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
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
        // Wall-clock guard: this test exercises the engine-driven peer-FIN
        // dispatch path. A regression in [AbstractPipelinedChannel]'s
        // deferred close-on-EOF handling can leave [SuspendBridgeHandler]
        // suspended forever on `bridge.read`, so without an explicit
        // [withTimeout] the test would hang until the CI job-level timeout
        // (~25 minutes) instead of failing fast with a stack trace. The
        // bound is generous (5 s) — the post-fix latency is sub-millisecond
        // on loopback.
        withTimeout(5.seconds) {
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            close(clientFd) // Client closes → EOF

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
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
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
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
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
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
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
            // Ownership transfer: write() takes the caller's reference and captures
            // (readerIndex, readableBytes) as a snapshot; it does not mutate the
            // live buffer indices. Matches Netty ChannelOutboundBuffer semantics.
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            val buf = DefaultAllocator.allocate(8)
            buf.writeByte(0x41)
            buf.writeByte(0x42)

            val observer = buf.retain() // retain so we can legally inspect after transfer
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
    fun `large payload flush writes all bytes`() = runBlocking {
        withTimeout(5.seconds) {
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            // 256KB payload — large enough to potentially trigger short writes
            // or EAGAIN when the kernel send buffer fills up.
            val payloadSize = 256 * 1024
            val payload = ByteArray(payloadSize) { (it % 256).toByte() }

            // Server writes the large payload
            val buf = DefaultAllocator.allocate(payloadSize)
            for (b in payload) buf.writeByte(b)
            ch.write(buf)
            ch.flush()

            // Client reads all bytes
            val received = PosixRawClient.rawReadBytes(clientFd, payloadSize)
            assertTrue(payload.contentEquals(received))

            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun `multiple write then single flush`() = runBlocking {
        withTimeout(5.seconds) {
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            // Buffer multiple writes, then flush once (exercises writev path).
            // 3 buffers of 64KB each = 192KB total via gather write.
            val chunkSize = 64 * 1024
            val bufs = (0 until 3).map { i ->
                DefaultAllocator.allocate(chunkSize).also { buf ->
                    for (j in 0 until chunkSize) buf.writeByte(((i * chunkSize + j) % 256).toByte())
                }
            }
            for (buf in bufs) ch.write(buf) // transfer each
            ch.flush()

            // Client reads all bytes
            val totalSize = chunkSize * 3
            val received = PosixRawClient.rawReadBytes(clientFd, totalSize)
            assertEquals(totalSize, received.size)

            // Verify content
            for (i in 0 until totalSize) {
                assertEquals((i % 256).toByte(), received[i], "Mismatch at byte $i")
            }

            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun `sequential flush reuses channel correctly`() = runBlocking {
        withTimeout(5.seconds) {
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            // Multiple write+flush cycles on the same channel to verify
            // that flush state (pendingWrites) is properly cleared and
            // the channel can be reused after EAGAIN recovery.
            for (round in 1..3) {
                val data = "round-$round"
                val buf = DefaultAllocator.allocate(64)
                for (b in data.encodeToByteArray()) buf.writeByte(b)
                ch.write(buf)
                ch.flush()

                val received = rawRead(clientFd, data.length)
                assertEquals(data, received, "Round $round mismatch")
            }

            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    // --- Half-close ---

    @Test
    fun shutdownOutputSendsFin() = runBlocking {
        withTimeout(5.seconds) {
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
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
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
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
    fun `shutdownOutput sends buffered writes before the FIN`() = runBlocking {
        // `write()` buffers without sending, so a half-close that follows it has
        // to drain the buffer before shutting the write side down. Issuing the
        // FIN first strands the bytes in `pendingWrites`: the peer observes EOF
        // with nothing before it, and the eventual flush writes to a socket
        // that is already shut down (EPIPE, logged and dropped).
        withTimeout(IO_OP_TIMEOUT_MS) {
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            val payload = "final"
            val bytes = payload.encodeToByteArray()
            val buf = DefaultAllocator.allocate(16)
            buf.writeByteArray(bytes, 0, bytes.size)
            ch.write(buf)

            // No flush() — the half-close is what has to get these bytes out.
            ch.shutdownOutput()

            assertEquals(payload, rawRead(clientFd, payload.length))
            assertEquals(ReadResult.Eof, PosixRawClient.rawReadOnce(clientFd, 1))

            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun `peer half-close lets the server still write a final response`() = runBlocking {
        // End-to-end guard for the Coroutine-mode auto-close removal. The
        // client half-closes its write side (shutdown(SHUT_WR)); the
        // server reads EOF (-1) and then writes a final response that the
        // client — read side still open — receives.
        //
        // keel has no half-close-vs-full-close branching: a peer FIN
        // always surfaces as `onReadClosed`. So this is not a test of
        // "half-close logic" — it guards an emergent property of the
        // *whole real engine stack* that the seam tests cannot reach:
        //
        //  - No layer (ReadinessIoTransport / KqueueEventLoop / the core
        //    AbstractPipelinedChannel) closes the channel's write side on
        //    a peer read-EOF. AbstractPipelinedChannelTest pins this for
        //    the core channel alone, over a fake transport; only a
        //    real-engine test catches an engine-layer regression that
        //    closes the fd on EV_EOF.
        //  - The real race between this first `read()` (which installs
        //    the bridge) and the EventLoop's `onPeerClosed` dispatch — the
        //    always-armed read of PR #467 — resolves correctly. The seam
        //    test fires `onReadClosed` synchronously and never runs that
        //    race; removing the `pendingClose` deferral relies on it being
        //    benign.
        //
        // Before the auto-close removal the accept()ed channel closed on
        // the EOF and this server write threw IllegalStateException.
        withTimeout(5.seconds) {
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val ch = server.accept()

            // Client half-closes — FIN sent, client read side stays open.
            PosixRawClient.rawShutdownWrite(clientFd)

            // Server observes EOF on the read side.
            val buf = DefaultAllocator.allocate(64)
            assertEquals(-1, ch.read(buf))
            buf.release()

            // The channel is still open: the server writes a final response.
            val response = DefaultAllocator.allocate(2)
            response.writeByte('o'.code.toByte())
            response.writeByte('k'.code.toByte())
            assertEquals(2, ch.write(response))
            ch.flush()

            // The client's read side is open — it receives the response.
            assertEquals("ok", rawRead(clientFd, 2))

            ch.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    // --- asSuspendSource/asSuspendSink ---

    @Test
    fun asSuspendSourceReadsData() = runBlocking {
        withTimeout(5.seconds) {
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
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
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
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
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
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
    fun echoRoundTripWithFlushCoalescingDisabled() = runBlocking {
        // Verifies that IoEngineConfig.flushCoalescing = false preserves
        // correctness — each keel-side flush drains synchronously via
        // performFlush() instead of scheduling a next-tick coalesce.
        withTimeout(5.seconds) {
            val engine = KqueueEngine(IoEngineConfig(flushCoalescing = false))
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val clientFd = connectRawClient(port)
            val serverCh = server.accept()

            rawWrite(clientFd, "hello")

            val readBuf = DefaultAllocator.allocate(64)
            val n = serverCh.read(readBuf)
            assertEquals(5, n)

            serverCh.write(readBuf)
            serverCh.flush()

            val echo = rawRead(clientFd, 5)
            assertEquals("hello", echo)

            serverCh.close()
            close(clientFd)
            server.close()
            engine.close()
        }
    }
}
