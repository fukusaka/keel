package io.github.fukusaka.keel.io

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class PushToSuspendSourceAdapterTest {

    /** A [PushSuspendSource] backed by a list of pre-filled [NativeBuf]s. */
    private class ListPushSource(bufs: List<NativeBuf>) : PushSuspendSource {
        private val queue = ArrayDeque(bufs)
        var closed = false
            private set

        override suspend fun readOwned(): NativeBuf? =
            if (queue.isNotEmpty()) queue.removeFirst() else null

        override fun close() {
            closed = true
            while (queue.isNotEmpty()) queue.removeFirst().release()
        }
    }

    private fun filledBuf(vararg bytes: Byte): NativeBuf {
        val buf = NativeBuf(bytes.size)
        for (b in bytes) buf.writeByte(b)
        return buf
    }

    @Test
    fun `read copies data from owned buffer to caller buffer`() = runBlocking {
        val source = ListPushSource(listOf(filledBuf(0x41, 0x42, 0x43)))
        val adapter = PushToSuspendSourceAdapter(source)

        val dst = NativeBuf(8)
        val n = adapter.read(dst)
        assertEquals(3, n)
        assertEquals('A'.code.toByte(), dst.readByte())
        assertEquals('B'.code.toByte(), dst.readByte())
        assertEquals('C'.code.toByte(), dst.readByte())

        dst.release()
        adapter.close()
    }

    @Test
    fun `read returns minus one on EOF`() = runBlocking {
        val source = ListPushSource(emptyList())
        val adapter = PushToSuspendSourceAdapter(source)

        val dst = NativeBuf(8)
        val n = adapter.read(dst)
        assertEquals(-1, n)

        dst.release()
        adapter.close()
    }

    @Test
    fun `read caps at writable bytes and retains leftover`() = runBlocking {
        val source = ListPushSource(listOf(filledBuf(1, 2, 3, 4, 5)))
        val adapter = PushToSuspendSourceAdapter(source)

        // First read: only 3 writable bytes, 2 bytes left over.
        val dst1 = NativeBuf(3)
        assertEquals(3, adapter.read(dst1))
        assertEquals(1.toByte(), dst1.readByte())
        assertEquals(2.toByte(), dst1.readByte())
        assertEquals(3.toByte(), dst1.readByte())
        dst1.release()

        // Second read: drains leftover (2 bytes) without calling readOwned().
        val dst2 = NativeBuf(8)
        assertEquals(2, adapter.read(dst2))
        assertEquals(4.toByte(), dst2.readByte())
        assertEquals(5.toByte(), dst2.readByte())
        dst2.release()

        // Third read: source is empty → EOF.
        val dst3 = NativeBuf(8)
        assertEquals(-1, adapter.read(dst3))
        dst3.release()

        adapter.close()
    }

    @Test
    fun `multiple reads drain multiple owned buffers`() = runBlocking {
        val source = ListPushSource(
            listOf(
                filledBuf(0x41, 0x42),
                filledBuf(0x43, 0x44),
            )
        )
        val adapter = PushToSuspendSourceAdapter(source)

        val dst = NativeBuf(8)
        assertEquals(2, adapter.read(dst))
        assertEquals(2, adapter.read(dst))
        assertEquals(-1, adapter.read(dst)) // EOF

        assertEquals('A'.code.toByte(), dst.readByte())
        assertEquals('B'.code.toByte(), dst.readByte())
        assertEquals('C'.code.toByte(), dst.readByte())
        assertEquals('D'.code.toByte(), dst.readByte())

        dst.release()
        adapter.close()
    }

    @Test
    fun `close delegates to push source`() = runBlocking {
        val source = ListPushSource(listOf(filledBuf(1)))
        val adapter = PushToSuspendSourceAdapter(source)

        adapter.close()
        assertEquals(true, source.closed)
    }
}
