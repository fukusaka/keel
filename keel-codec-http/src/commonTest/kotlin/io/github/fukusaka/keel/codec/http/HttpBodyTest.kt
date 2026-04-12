package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.EmptyIoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HttpBodyTest {

    @Test
    fun `HttpBody wraps IoBuf and exposes readable bytes`() {
        val buf = DefaultAllocator.allocate(16)
        buf.writeByte(0x41) // 'A'
        buf.writeByte(0x42) // 'B'
        buf.writeByte(0x43) // 'C'
        val body = HttpBody(buf)
        assertEquals(3, body.content.readableBytes)
        buf.release()
    }

    @Test
    fun `HttpBodyEnd EMPTY singleton is trailer-less and zero-byte`() {
        val empty = HttpBodyEnd.EMPTY
        assertEquals(0, empty.content.readableBytes)
        assertSame(HttpHeaders.EMPTY, empty.trailers)
        assertTrue(empty.trailers.isEmpty)
    }

    @Test
    fun `HttpBodyEnd preserves trailers`() {
        val trailers = HttpHeaders.build {
            add("Checksum", "abc123")
        }
        val bodyEnd = HttpBodyEnd(EmptyIoBuf, trailers)
        assertEquals("abc123", bodyEnd.trailers["Checksum"])
        assertEquals(1, bodyEnd.trailers.size)
    }

    @Test
    fun `HttpBody toString reports byte count`() {
        val buf = DefaultAllocator.allocate(8)
        buf.writeByte(0x01)
        buf.writeByte(0x02)
        val body = HttpBody(buf)
        assertEquals("HttpBody(2 bytes)", body.toString())
        buf.release()
    }

    @Test
    fun `HttpBody is assignable to HttpMessage and HttpBodyEnd to HttpBody`() {
        val buf = DefaultAllocator.allocate(4)
        val body: HttpMessage = HttpBody(buf)
        assertIs<HttpMessage>(body)
        assertIs<HttpBody>(body)

        val bodyEnd: HttpBody = HttpBodyEnd.EMPTY
        assertIs<HttpBody>(bodyEnd)
        assertIs<HttpBodyEnd>(bodyEnd)
        assertIs<HttpMessage>(bodyEnd)
        buf.release()
    }

    @Test
    fun `HttpBodyEnd EMPTY release is idempotent no-op`() {
        // EmptyIoBuf.release() returns false (never freed) and is safe
        // to call repeatedly.
        val empty = HttpBodyEnd.EMPTY
        empty.content.release()
        empty.content.release()
        assertEquals(0, empty.content.readableBytes)
    }

    @Test
    fun `HttpBody retains and releases IoBuf correctly`() {
        val buf = DefaultAllocator.allocate(8)
        buf.writeByte(0x41)
        val body = HttpBody(buf)

        body.content.retain()
        body.content.release() // decrement from 2 to 1
        assertEquals(1, body.content.readableBytes)

        body.content.release() // decrement from 1 to 0 — freed
    }
}
