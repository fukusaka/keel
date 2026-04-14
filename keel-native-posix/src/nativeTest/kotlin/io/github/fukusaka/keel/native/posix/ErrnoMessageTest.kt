package io.github.fukusaka.keel.native.posix

import platform.posix.EAGAIN
import platform.posix.EINVAL
import platform.posix.ENOENT
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ErrnoMessageTest {

    @Test
    fun `errnoMessage includes the numeric errno`() {
        val msg = errnoMessage(ENOENT)
        assertContains(msg, "errno=$ENOENT")
    }

    @Test
    fun `errnoMessage produces non-empty descriptive text`() {
        val msg = errnoMessage(EINVAL)
        // Symbolic description varies by libc, but it is always non-empty
        // and ends with the "(errno=N)" suffix.
        assertTrue(msg.length > "(errno=$EINVAL)".length, "message: $msg")
        assertTrue(msg.endsWith("(errno=$EINVAL)"), "message: $msg")
    }

    @Test
    fun `errnoMessage handles a zero errno without crashing`() {
        // errno=0 conventionally means "Success"; both glibc and Darwin
        // libc return a non-empty string. The contract here is just that
        // the wrapper does not crash and embeds errno=0.
        val msg = errnoMessage(0)
        assertContains(msg, "errno=0")
    }

    @Test
    fun `errnoMessage handles an unknown errno without crashing`() {
        // Most libc implementations return "Unknown error N" for out-of-range
        // values. Verify the wrapper still embeds the numeric form.
        val msg = errnoMessage(UNKNOWN_ERRNO)
        assertContains(msg, "errno=$UNKNOWN_ERRNO")
    }

    @Test
    fun `errnoMessage produces distinct messages for different errnos`() {
        val a = errnoMessage(ENOENT)
        val b = errnoMessage(EAGAIN)
        // Both end with their respective numeric suffix, so the strings
        // are guaranteed to differ at the trailing token.
        assertTrue(a != b, "expected distinct messages, got: $a vs $b")
    }

    private companion object {
        private const val UNKNOWN_ERRNO = 9999
    }
}
