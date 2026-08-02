package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBufAsciiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lookup API (`get` / `contains` / `getString` / `getAll` / `remove`)
 * accepts any [CharSequence] — including a zero-copy [IoBufAsciiText]
 * view — so a caller already holding a CharSequence sourced from
 * another header value, a URI path slice, or any byte-range view does
 * not need to materialise it to `String` first.
 *
 * The test compares lookups by `String`, `StringBuilder`, and
 * `IoBufAsciiText` for the same logical name; all three must agree on
 * the matched entry.
 */
class HttpHeadersCharSequenceLookupTest {

    @Test
    fun `lookup with IoBufAsciiText key returns the same value as a String key`() {
        val headers = HttpHeaders.build {
            add("Content-Type", "application/json")
            add("Host", "example.com")
            add("X-Trace-Id", "abc-123")
        }

        try {
            val buf = DefaultAllocator.allocate(64)
            try {
                val nameBytes = "Content-Type".encodeToByteArray()
                buf.writeByteArray(nameBytes, 0, nameBytes.size)
                val viewKey: CharSequence = IoBufAsciiText(buf, 0, nameBytes.size)
                val stringKey = "Content-Type"

                assertEquals(headers.getString(stringKey), headers.getString(viewKey))
                assertEquals(headers[stringKey]?.toString(), headers[viewKey]?.toString())
                assertEquals(stringKey in headers, viewKey in headers)
            } finally {
                buf.release()
            }
        } finally {
            headers.release()
        }
    }

    @Test
    fun `lookup with StringBuilder key behaves identically to String key`() {
        val headers = HttpHeaders.build {
            add("Accept-Language", "en-US,en;q=0.9")
        }
        try {
            val builder = StringBuilder().apply {
                append("Accept-")
                append("Language")
            }
            assertEquals("en-US,en;q=0.9", headers.getString(builder))
            assertTrue(builder in headers)
        } finally {
            headers.release()
        }
    }

    @Test
    fun `case-insensitive lookup with CharSequence key matches`() {
        val headers = HttpHeaders.build {
            add("Content-Type", "text/html")
        }
        try {
            val lower: CharSequence = StringBuilder("content-type")
            val upper: CharSequence = StringBuilder("CONTENT-TYPE")
            assertEquals("text/html", headers.getString(lower))
            assertEquals("text/html", headers.getString(upper))
            assertTrue(lower in headers)
            assertTrue(upper in headers)
        } finally {
            headers.release()
        }
    }

    @Test
    fun `getAll with CharSequence key returns every matching value`() {
        val headers = HttpHeaders.build {
            add("Set-Cookie", "a=1")
            add("Set-Cookie", "b=2")
            add("Set-Cookie", "c=3")
            add("Content-Type", "text/plain")
        }
        try {
            val viewKey: CharSequence = StringBuilder("Set-Cookie")
            val values = headers.getAll(viewKey)
            assertEquals(listOf("a=1", "b=2", "c=3"), values)
        } finally {
            headers.release()
        }
    }

    @Test
    fun `remove with CharSequence key removes the entry`() {
        val headers = HttpHeaders.build {
            add("X-Trace-Id", "abc")
            add("Host", "example.com")
        }
        try {
            val viewKey: CharSequence = StringBuilder("X-Trace-Id")
            headers.remove(viewKey)
            assertFalse(viewKey in headers)
            assertFalse("X-Trace-Id" in headers)
            // Other headers untouched.
            assertEquals("example.com", headers.getString("Host"))
        } finally {
            headers.release()
        }
    }

    @Test
    fun `missing CharSequence key returns null without error`() {
        val headers = HttpHeaders.build { add("Host", "example.com") }
        try {
            val missing: CharSequence = StringBuilder("X-Does-Not-Exist")
            assertNull(headers[missing])
            assertNull(headers.getString(missing))
            assertFalse(missing in headers)
            assertTrue(headers.getAll(missing).isEmpty())
        } finally {
            headers.release()
        }
    }

    @Test
    fun `add and set CharSequence overloads materialise to String entries`() {
        val name: CharSequence = StringBuilder("X-Custom")
        val value: CharSequence = StringBuilder("first")
        val headers = HttpHeaders().apply {
            add(name, value)
        }
        try {
            assertEquals("first", headers.getString("X-Custom"))
            // set() overwrites — only one entry remains.
            headers[name] = StringBuilder("second") as CharSequence
            assertEquals("second", headers.getString("X-Custom"))
            assertEquals(1, headers.size)
            // Materialised name round-trips to the original String form.
            assertNotNull(headers["X-Custom"])
        } finally {
            headers.release()
        }
    }
}
