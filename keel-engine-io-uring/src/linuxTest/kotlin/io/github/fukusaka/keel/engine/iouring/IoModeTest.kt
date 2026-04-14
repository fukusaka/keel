package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.IoEngineConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [IoMode] selection, [IoUringCapabilities] invariants,
 * and SENDMSG_ZC gather write functionality.
 */
class IoModeTest {

    // --- IoUringCapabilities invariants ---

    @Test
    fun `sendmsgZc true requires sendZc true`() {
        // Verify that constructing capabilities with sendmsgZc=true, sendZc=false
        // is a user error (not enforced at type level, but documented).
        // The detect() method guarantees this invariant.
        val valid = IoUringCapabilities(sendZc = true, sendmsgZc = true)
        assertTrue(valid.sendZc)
        assertTrue(valid.sendmsgZc)

        val sendZcOnly = IoUringCapabilities(sendZc = true, sendmsgZc = false)
        assertTrue(sendZcOnly.sendZc)
        assertEquals(false, sendZcOnly.sendmsgZc)
    }

    @Test
    fun `capabilities MINIMAL has all features disabled`() {
        val caps = IoUringCapabilities.MINIMAL
        assertEquals(false, caps.sendZc)
        assertEquals(false, caps.sendmsgZc)
        assertEquals(false, caps.multishotAccept)
        assertEquals(false, caps.multishotRecv)
        assertEquals(false, caps.providedBufferRing)
    }

    @Test
    fun `SENDMSG_ZC falls back to CQE when sendmsgZc capability is false`() = runBlocking {
        // Force sendmsgZc=false but keep other capabilities at default.
        // SENDMSG_ZC mode should fall back to CQE.
        // threads=2: CQE flush + awaitPendingFlush requires separate EventLoops
        // for client and server to avoid deadlock (suspend blocks CQE drain).
        val defaultCaps = IoUringCapabilities()
        val engine = IoUringEngine(
            config = IoEngineConfig(threads = 2),
            capabilities = defaultCaps.copy(sendZc = false, sendmsgZc = false),
            writeModeSelector = IoModeSelectors.SENDMSG_ZC,
        )
        try {
            echoSmall(engine)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `SEND_ZC falls back to CQE when sendZc capability is false`() = runBlocking {
        val defaultCaps = IoUringCapabilities()
        val engine = IoUringEngine(
            config = IoEngineConfig(threads = 2),
            capabilities = defaultCaps.copy(sendZc = false, sendmsgZc = false),
            writeModeSelector = IoModeSelectors.SEND_ZC,
        )
        try {
            echoSmall(engine)
        } finally {
            engine.close()
        }
    }

    private suspend fun echoSmall(engine: IoUringEngine) = withTimeout(10_000) {
        val server = engine.bind("127.0.0.1", 0)
        val port = server.localAddress.port
        val client = engine.connect("127.0.0.1", port)
        val conn = server.accept()

        val writeBuf = DefaultAllocator.allocate(16)
        writeBuf.writeByte(0x42)
        client.write(writeBuf)
        client.flush()
        writeBuf.release()

        val readBuf = DefaultAllocator.allocate(16)
        val n = conn.read(readBuf)
        assertTrue(n > 0)

        readBuf.release()
        client.close()
        conn.close()
        server.close()
    }

    // --- Echo round-trip with specific IoMode ---

    @Test
    fun `echo with CQE mode`() = runBlocking {
        echoWithMode(IoModeSelectors.CQE)
    }

    @Test
    fun `echo with SEND_ZC mode`() = runBlocking {
        echoWithMode(IoModeSelectors.SEND_ZC)
    }

    @Test
    fun `echo with SENDMSG_ZC mode`() = runBlocking {
        echoWithMode(IoModeSelectors.SENDMSG_ZC)
    }

    @Test
    fun `echo with FALLBACK_CQE mode`() = runBlocking {
        echoWithMode(IoModeSelectors.FALLBACK_CQE)
    }

    @Test
    fun `large payload with SENDMSG_ZC mode`() = runBlocking {
        // 100KB payload triggers gather write path (multiple 8KB buffers).
        echoWithMode(IoModeSelectors.SENDMSG_ZC, payloadSize = 100_000)
    }

    @Test
    fun `large payload with CQE mode`() = runBlocking {
        echoWithMode(IoModeSelectors.CQE, payloadSize = 100_000)
    }

    @Test
    fun `large payload with SEND_ZC mode`() = runBlocking {
        echoWithMode(IoModeSelectors.SEND_ZC, payloadSize = 100_000)
    }

    private suspend fun echoWithMode(selector: IoModeSelector, payloadSize: Int = 13) = withTimeout(10_000) {
        // threads=2: CQE/SEND_ZC flush suspends the EventLoop via
        // awaitPendingFlush. Client and server must be on separate
        // EventLoops to avoid deadlock.
        val engine = IoUringEngine(
            config = IoEngineConfig(threads = 2),
            writeModeSelector = selector,
        )
        try {
            val server = engine.bind("127.0.0.1", 0)
            val port = server.localAddress.port

            val client = engine.connect("127.0.0.1", port)
            val conn = server.accept()

            // Write from client
            val payload = ByteArray(payloadSize) { (it % 256).toByte() }
            val writeBuf = DefaultAllocator.allocate(payloadSize)
            for (b in payload) writeBuf.writeByte(b)
            client.write(writeBuf)
            client.flush()
            writeBuf.release()

            // Read on server
            val readBuf = DefaultAllocator.allocate(payloadSize + 1024)
            var totalRead = 0
            while (totalRead < payloadSize) {
                readBuf.readerIndex = 0
                readBuf.writerIndex = 0
                val n = conn.read(readBuf)
                assertTrue(n > 0, "Expected data, got n=$n")
                totalRead += n
            }

            // Echo back from server
            val echoBuf = DefaultAllocator.allocate(payloadSize)
            for (b in payload) echoBuf.writeByte(b)
            conn.write(echoBuf)
            conn.flush()
            echoBuf.release()

            // Read echo on client
            val responseBuf = DefaultAllocator.allocate(payloadSize + 1024)
            var totalResponse = 0
            while (totalResponse < payloadSize) {
                responseBuf.readerIndex = 0
                responseBuf.writerIndex = 0
                val n = client.read(responseBuf)
                assertTrue(n > 0, "Expected echo data, got n=$n")
                totalResponse += n
            }
            assertEquals(payloadSize, totalResponse)

            readBuf.release()
            responseBuf.release()
            client.close()
            conn.close()
            server.close()
        } finally {
            engine.close()
        }
    }
}
