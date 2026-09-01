package io.github.fukusaka.keel.testing.engine

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Loopback tests for [InMemoryEngine]: a `bindPipeline` listener plus a
 * `connect`-ed client, cross-wired entirely in memory.
 *
 * The in-memory transport's `ioDispatcher` is
 * [kotlinx.coroutines.Dispatchers.Unconfined], so a `flush` delivers to
 * the peer synchronously within the calling coroutine — the round-trip
 * has no wall-clock dependency. A [withTimeout] still bounds each test as
 * a defence against an accidental hang in the suspend `read` path.
 */
class InMemoryEngineTest {

    /** Async budget for an in-memory round-trip (generous; the path is synchronous). */
    private val asyncBudget = 5.seconds

    /** Wraps [text] as an [IoBuf] from the default allocator. */
    private fun bufOf(text: String): IoBuf {
        val bytes = text.encodeToByteArray()
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    /** Reads all readable bytes of [buf] as a UTF-8 string. */
    private fun IoBuf.readString(): String {
        val bytes = ByteArray(readableBytes)
        readByteArray(bytes, 0, bytes.size)
        return bytes.decodeToString()
    }

    /**
     * An [InboundHandler] that echoes every inbound [IoBuf] straight back
     * out — the minimal server-side pipeline for the loopback test.
     */
    private class EchoHandler : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            ctx.propagateWrite(msg)
            ctx.propagateFlush()
        }
    }

    @Test
    fun `connect followed by write surfaces the bytes echoed back through the listener pipeline`() =
        runTest {
            withTimeout(asyncBudget) {
                val engine = InMemoryEngine()
                try {
                    val server = engine.bindPipeline(InetSocketAddress("127.0.0.1", 0)) { channel ->
                        channel.pipeline.addLast("echo", EchoHandler())
                    }
                    val client = engine.connect(server.localAddress)

                    client.write(bufOf("ping"))
                    client.flush()

                    val readBuf = DefaultAllocator.allocate(64)
                    val n = client.read(readBuf)
                    assertEquals(4, n)
                    assertEquals("ping", readBuf.readString())
                    readBuf.release()
                    client.close()
                } finally {
                    engine.close()
                }
            }
        }

    @Test
    fun `a delivered burst is followed by the boundary that closes it`() = runTest {
        withTimeout(asyncBudget) {
            val engine = InMemoryEngine()
            try {
                val seen = mutableListOf<String>()
                val boundary = CompletableDeferred<List<String>>()
                val server = engine.bindPipeline(InetSocketAddress("127.0.0.1", 0)) { channel ->
                    channel.pipeline.addLast(
                        "recorder",
                        object : InboundHandler {
                            override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                                seen.add("read")
                                (msg as? IoBuf)?.release()
                            }

                            override fun onReadComplete(ctx: PipelineHandlerContext) {
                                seen.add("batchEnd")
                                boundary.complete(seen.toList())
                            }
                        },
                    )
                }
                val client = engine.connect(server.localAddress)

                client.write(bufOf("ping"))
                client.flush()

                // This transport stands in for a socket engine, so it owes a
                // handler the same batch boundary one of them would send —
                // its drain hands over everything the peer had in one pass,
                // and that pass is the batch.
                assertEquals(listOf("read", "batchEnd"), boundary.await())
                client.close()
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun `bindPipeline assigns a synthetic ephemeral port when binding to port zero`() = runTest(timeout = 15.seconds) {
        val engine = InMemoryEngine()
        try {
            val server = engine.bindPipeline(InetSocketAddress("127.0.0.1", 0)) { }
            val address = server.localAddress
            assertTrue(address is InetSocketAddress)
            assertTrue(address.port != 0, "expected a synthetic non-zero port, got ${address.port}")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `connect to an address with no registered listener is refused`() = runTest(timeout = 15.seconds) {
        val engine = InMemoryEngine()
        try {
            assertFailsWith<IllegalStateException> {
                engine.connect(InetSocketAddress("127.0.0.1", 65000))
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `binding the same resolved address twice is rejected`() = runTest(timeout = 15.seconds) {
        val engine = InMemoryEngine()
        try {
            engine.bindPipeline(InetSocketAddress("127.0.0.1", 8080)) { }
            assertFailsWith<IllegalStateException> {
                engine.bindPipeline(InetSocketAddress("127.0.0.1", 8080)) { }
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `multiple messages on one connection are each echoed back in order`() = runTest {
        withTimeout(asyncBudget) {
            val engine = InMemoryEngine()
            try {
                val server = engine.bindPipeline(InetSocketAddress("127.0.0.1", 0)) { channel ->
                    channel.pipeline.addLast("echo", EchoHandler())
                }
                val client = engine.connect(server.localAddress)
                for (msg in listOf("a", "bb", "ccc")) {
                    client.write(bufOf(msg))
                    client.flush()
                    val readBuf = DefaultAllocator.allocate(64)
                    val n = client.read(readBuf)
                    assertEquals(msg.length, n)
                    assertEquals(msg, readBuf.readString())
                    readBuf.release()
                }
                client.close()
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun `bind throws because only pipeline-mode binding is supported`() = runTest(timeout = 15.seconds) {
        val engine = InMemoryEngine()
        try {
            assertFailsWith<UnsupportedOperationException> {
                engine.bind(InetSocketAddress("127.0.0.1", 0))
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `connect after the engine is closed is rejected`() = runTest(timeout = 15.seconds) {
        val engine = InMemoryEngine()
        val server = engine.bindPipeline(InetSocketAddress("127.0.0.1", 0)) { }
        engine.close()
        assertFailsWith<IllegalStateException> {
            engine.connect(server.localAddress)
        }
    }
}
