package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.core.BufferedSuspendSink
import io.github.fukusaka.keel.core.HeapAllocator
import io.github.fukusaka.keel.core.NativeBuf
import io.github.fukusaka.keel.core.SuspendSink
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class SuspendHttpWriterTest {

    private class CollectingSink : SuspendSink {
        val chunks = mutableListOf<ByteArray>()
        override suspend fun write(buf: NativeBuf): Int {
            val bytes = ByteArray(buf.readableBytes)
            for (i in bytes.indices) bytes[i] = buf.readByte()
            chunks.add(bytes)
            return bytes.size
        }
        override suspend fun flush() {}
        override fun close() {}

        fun collected(): String = chunks.flatMap { it.toList() }
            .toByteArray().decodeToString()
    }

    @Test
    fun `writeResponseHead suspend variant writes status and headers`() = runBlocking {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, HeapAllocator)

        val headers = HttpHeaders()
            .add("Content-Length", "13")
            .add("Content-Type", "text/plain")

        writeResponseHead(
            status = HttpStatus(200),
            version = HttpVersion.HTTP_1_1,
            headers = headers,
            sink = buffered,
        )
        buffered.flush()

        val output = sink.collected()
        assertTrue(output.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(output.contains("Content-Length: 13\r\n"))
        assertTrue(output.contains("Content-Type: text/plain\r\n"))
        assertTrue(output.endsWith("\r\n\r\n"))
        buffered.close()
    }
}
