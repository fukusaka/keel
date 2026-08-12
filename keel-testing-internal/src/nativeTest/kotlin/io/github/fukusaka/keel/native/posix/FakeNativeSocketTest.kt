package io.github.fukusaka.keel.native.posix

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import platform.posix.ECONNREFUSED
import platform.posix.ECONNRESET
import platform.posix.EPIPE
import platform.posix.SHUT_WR
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class FakeNativeSocketTest {

    /** Scratch 1-byte buffer — the fake ignores buffer contents. */
    private inline fun <T> withDummyBuf(block: (CPointer<ByteVar>) -> T): T = memScoped {
        block(allocArray<ByteVar>(1))
    }

    @Test
    fun `read returns scripted responses in order then falls back to default`() {
        val fake = FakeNativeSocket()
        fake.enqueueRead(fd = 3, ReadResult.Bytes(4), ReadResult.Eof)
        withDummyBuf { buf ->
            assertEquals(ReadResult.Bytes(4), fake.read(3, buf, 100))
            assertEquals(ReadResult.Eof, fake.read(3, buf, 100))
            // Queue drained — falls back to defaultRead (WouldBlock).
            assertEquals(ReadResult.WouldBlock, fake.read(3, buf, 100))
        }
        assertEquals(3, fake.readCalls)
    }

    @Test
    fun `defaultRead override is honoured when queue is empty`() {
        val fake = FakeNativeSocket().apply { defaultRead = ReadResult.Failed(ECONNRESET) }
        withDummyBuf { buf ->
            assertEquals(ReadResult.Failed(ECONNRESET), fake.read(42, buf, 10))
        }
    }

    @Test
    fun `read queues are per-fd independent`() {
        val fake = FakeNativeSocket()
        fake.enqueueRead(fd = 3, ReadResult.Bytes(1))
        fake.enqueueRead(fd = 4, ReadResult.Eof)
        withDummyBuf { buf ->
            assertEquals(ReadResult.Bytes(1), fake.read(3, buf, 10))
            assertEquals(ReadResult.Eof, fake.read(4, buf, 10))
        }
    }

    @Test
    fun `write returns scripted WriteResults and tracks call count`() {
        val fake = FakeNativeSocket()
        fake.enqueueWrite(fd = 7, WriteResult.Written(8), WriteResult.WouldBlock, WriteResult.Failed(EPIPE))
        withDummyBuf { buf ->
            assertEquals(WriteResult.Written(8), fake.write(7, buf, 100))
            assertEquals(WriteResult.WouldBlock, fake.write(7, buf, 100))
            assertEquals(WriteResult.Failed(EPIPE), fake.write(7, buf, 100))
        }
        assertEquals(3, fake.writeCalls)
    }

    @Test
    fun `accept returns scripted AcceptResults`() {
        val fake = FakeNativeSocket()
        fake.enqueueAccept(
            serverFd = 10,
            AcceptResult.Accepted(fd = 11),
            AcceptResult.WouldBlock,
            AcceptResult.Failed(errno = 22),
        )
        assertEquals(AcceptResult.Accepted(11), fake.accept(10))
        assertEquals(AcceptResult.WouldBlock, fake.accept(10))
        assertEquals(AcceptResult.Failed(22), fake.accept(10))
        assertEquals(3, fake.acceptCalls)
    }

    @Test
    fun `connect defaults to InProgress`() {
        val fake = FakeNativeSocket()
        withDummyBuf { addr ->
            assertEquals(ConnectResult.InProgress, fake.connect(5, addr, 16))
        }
    }

    @Test
    fun `connect returns scripted Failed errno`() {
        val fake = FakeNativeSocket()
        fake.enqueueConnect(fd = 5, ConnectResult.Failed(ECONNREFUSED))
        withDummyBuf { addr ->
            val result = fake.connect(5, addr, 16)
            assertEquals(ConnectResult.Failed(ECONNREFUSED), result)
        }
    }

    @Test
    fun `shutdown defaults to Ok and records call count`() {
        val fake = FakeNativeSocket()
        assertEquals(ShutdownResult.Ok, fake.shutdown(9, SHUT_WR))
        fake.enqueueShutdown(fd = 9, ShutdownResult.Failed(EPIPE))
        assertEquals(ShutdownResult.Failed(EPIPE), fake.shutdown(9, SHUT_WR))
        assertEquals(2, fake.shutdownCalls)
    }

    @Test
    fun `close records fds in invocation order`() {
        val fake = FakeNativeSocket()
        fake.close(3)
        fake.close(5)
        fake.close(7)
        assertEquals(listOf(3, 5, 7), fake.closedFds)
        assertEquals(3, fake.closeCalls)
    }

    @Test
    fun `close returns scripted CloseResult then default Ok`() {
        val fake = FakeNativeSocket()
        fake.enqueueClose(fd = 3, CloseResult.Failed(errno = 9))
        assertEquals(CloseResult.Failed(9), fake.close(3))
        // Queue drained → default Ok.
        assertEquals(CloseResult.Ok, fake.close(3))
    }

    @Test
    fun `assertNoDoubleClose passes when each fd closed at most once`() {
        val fake = FakeNativeSocket()
        fake.close(1)
        fake.close(2)
        fake.assertNoDoubleClose()
    }

    @Test
    fun `assertNoDoubleClose throws when a fd is closed twice`() {
        val fake = FakeNativeSocket()
        fake.close(3)
        fake.close(3)
        val ex = assertFailsWith<IllegalStateException> { fake.assertNoDoubleClose() }
        assertTrue(ex.message!!.contains("fd=3"))
    }

    @Test
    fun `assertAllConsumed passes when every scripted response was popped`() {
        val fake = FakeNativeSocket()
        fake.enqueueRead(fd = 3, ReadResult.Eof)
        withDummyBuf { buf -> fake.read(3, buf, 10) }
        fake.assertAllConsumed()
    }

    @Test
    fun `assertAllConsumed reports unconsumed entries across multiple queues`() {
        val fake = FakeNativeSocket()
        fake.enqueueRead(fd = 3, ReadResult.Eof)
        fake.enqueueWrite(fd = 4, WriteResult.Failed(EPIPE), WriteResult.Failed(EPIPE))
        val ex = assertFailsWith<IllegalStateException> { fake.assertAllConsumed() }
        val msg = ex.message!!
        assertContains(msg, "read(fd=3): 1 remaining")
        assertContains(msg, "write(fd=4): 2 remaining")
    }

    @Test
    fun `writev and send share defaultWrite but have independent queues`() {
        val fake = FakeNativeSocket().apply { defaultWrite = WriteResult.Written(100) }
        fake.enqueueWritev(fd = 3, WriteResult.Written(50))
        memScoped {
            val bases = allocArray<CPointerVar<ByteVar>>(1)
            val lens = allocArray<ULongVar>(1)
            assertEquals(WriteResult.Written(50), fake.writev(3, bases, lens, 0))
            // writev queue drained — falls back to defaultWrite.
            assertEquals(WriteResult.Written(100), fake.writev(3, bases, lens, 0))
        }
        // send never scripted — also falls back to defaultWrite.
        withDummyBuf { buf ->
            assertEquals(WriteResult.Written(100), fake.send(3, buf, 10, 0))
        }
        assertEquals(2, fake.writevCalls)
        assertEquals(1, fake.sendCalls)
    }

    @Test
    fun `assertAllConsumed reports a scripted fault that never fired`() {
        // The sharper half of the check: a test whose fault never fires asserts
        // what a failure costs against a run that had no failure in it, and
        // stays green against a build that never fixed anything.
        val fake = FakeNativeSocket().apply { flushThrowsOnce = IllegalStateException("never reached") }

        val failure = assertFailsWith<IllegalStateException> { fake.assertAllConsumed() }
        assertContains(failure.message.orEmpty(), "never fired")
    }

    @Test
    fun `assertAllConsumed reports a flush-failure delay with no failure behind it`() {
        // The counter only decrements while the fault is armed, so this
        // combination never fires and the fault check above cannot see it.
        val fake = FakeNativeSocket().apply { flushThrowsAfterCalls = 2 }

        val failure = assertFailsWith<IllegalStateException> { fake.assertAllConsumed() }
        assertContains(failure.message.orEmpty(), "without a failure to delay")
    }

    @Test
    fun `assertAllConsumed passes once the delayed fault has fired`() {
        val fake = FakeNativeSocket().apply {
            enqueueWrite(3, WriteResult.Written(1))
            flushThrowsOnce = IllegalStateException("delayed")
            flushThrowsAfterCalls = 1
        }
        withDummyBuf { buf ->
            fake.write(3, buf, 1)
            assertFailsWith<IllegalStateException> { fake.write(3, buf, 1) }
        }

        fake.assertAllConsumed()
    }
}
