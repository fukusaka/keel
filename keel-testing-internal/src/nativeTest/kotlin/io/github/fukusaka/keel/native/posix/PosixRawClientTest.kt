package io.github.fukusaka.keel.native.posix

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.AF_UNIX
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.socketpair
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * What [PosixRawClient.rawReadUntil] does with a payload that is not yet whole.
 *
 * The class KDoc argues this file needs no self-test because the engine
 * integration tests drive a real server through it, so a regression shows up as
 * a wrong server observation. That holds for connect / read / write. It does not
 * reach the decode: every payload those tests send is ASCII, and loopback does
 * not split a two-byte string, so the one branch that only a multi-byte
 * character can reach is never taken.
 *
 * Measured: replacing the decode with `throwOnInvalidSequence = true` — removing
 * the exact property the KDoc names — leaves the whole kqueue suite green at
 * 205 of 205. Hence this.
 */
@OptIn(ExperimentalForeignApi::class)
class PosixRawClientTest {

    /**
     * The predicate runs on every read that delivered bytes, so it sees a
     * character that arrived in pieces. The decode must substitute rather than
     * throw: substituting defers the predicate to the next read, which is when
     * the character is whole; throwing ends the call on a payload that was
     * merely early.
     *
     * A socket pair with the last byte withheld reproduces that first read
     * exactly, without a second thread — the predicate then never matches and
     * the call ends on its own timer, which is the point: it *ends*, rather
     * than throwing on the way. A socket, not a pipe: `SO_RCVTIMEO` is a
     * socket option, so on a pipe the second read blocks forever (measured —
     * the first draft of this test hung for fifty minutes).
     */
    @Test
    fun `a character split across reads defers the predicate instead of failing the call`() {
        val (readFd, writeFd) = newSocketPair()
        try {
            val whole = "あ".encodeToByteArray()
            assertEquals(3, whole.size, "the fixture needs a multi-byte character")
            writeBytes(writeFd, whole.copyOfRange(0, 2))

            var predicateSawFffd = false
            val result = PosixRawClient.rawReadUntil(readFd, 16, SHORT_TIMEOUT) { soFar ->
                if (soFar.contains(REPLACEMENT)) predicateSawFffd = true
                soFar.endsWith("あ")
            }

            assertTrue(predicateSawFffd, "the predicate must have run on the partial character")
            assertTrue(result.contains(REPLACEMENT), "the partial character decodes to U+FFFD, got: ${result.codes()}")
            assertFalse(result.endsWith("あ"), "a partial character must not read as the whole one")
        } finally {
            close(writeFd)
            close(readFd)
        }
    }

    /**
     * The other half of the same sentence: the read that follows a deferred
     * predicate is what makes the character whole. Writing the last byte from
     * inside the predicate reproduces that second delivery without a thread —
     * the predicate runs between reads, which is exactly where a peer's next
     * write would land.
     *
     * Measured: with the loop removed — returning after the first read that
     * delivered bytes — both this module and the kqueue suite stayed green, so
     * nothing else pins it.
     */
    @Test
    fun `a character completed by a later read decodes whole`() {
        val (readFd, writeFd) = newSocketPair()
        try {
            val whole = "あ".encodeToByteArray()
            writeBytes(writeFd, whole.copyOfRange(0, 2))

            var sentLastByte = false
            val result = PosixRawClient.rawReadUntil(readFd, 16, SHORT_TIMEOUT) { soFar ->
                if (!sentLastByte) {
                    sentLastByte = true
                    writeBytes(writeFd, whole.copyOfRange(2, 3))
                }
                soFar.endsWith("あ")
            }

            assertEquals("あ", result, "the bytes of one character, read in two goes, are one character")
        } finally {
            close(writeFd)
            close(readFd)
        }
    }

    /** The predicate ends the call as soon as it holds, without waiting for the timer. */
    @Test
    fun `a satisfied predicate ends the read without waiting for the timeout`() {
        val (readFd, writeFd) = newSocketPair()
        try {
            writeBytes(writeFd, "HEAD\r\n\r\n".encodeToByteArray())
            val result = PosixRawClient.rawReadUntil(readFd, 64, SHORT_TIMEOUT) { it.endsWith("\r\n\r\n") }
            assertEquals("HEAD\r\n\r\n", result)
        } finally {
            close(writeFd)
            close(readFd)
        }
    }

    private fun newSocketPair(): Pair<Int, Int> {
        val fds = IntArray(2)
        val ok = fds.usePinned { socketpair(AF_UNIX, SOCK_STREAM, 0, it.addressOf(0)) == 0 }
        check(ok) { "socketpair() failed" }
        return fds[0] to fds[1]
    }

    private fun writeBytes(fd: Int, bytes: ByteArray) {
        bytes.usePinned { pinned ->
            val written = write(fd, pinned.addressOf(0), bytes.size.toULong())
            check(written.toInt() == bytes.size) { "short write: $written of ${bytes.size}" }
        }
    }

    private fun String.codes(): String = map { it.code.toString(16) }.joinToString(",")

    private companion object {
        /** Long enough that a delivered byte is never missed, short enough to pay on every run. */
        val SHORT_TIMEOUT = 200.milliseconds

        const val REPLACEMENT = '�'
    }
}
