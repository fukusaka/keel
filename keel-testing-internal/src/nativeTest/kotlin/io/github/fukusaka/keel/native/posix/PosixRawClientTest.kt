package io.github.fukusaka.keel.native.posix

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import kotlin.time.Duration.Companion.seconds

/**
 * The parts of [PosixRawClient.rawReadUntil] that no caller of it exercises.
 *
 * The class KDoc argues this file needs no self-test because the engine
 * integration tests drive a real server through it, so a regression shows up as
 * a wrong server observation. That holds for connect / read / write. It does not
 * reach the decode: every payload those tests send is ASCII, and loopback does
 * not split a two-byte string, so the one branch that only a multi-byte
 * character can reach is never taken.
 *
 * The same holds for the predicate's own effect: removing the early return, so
 * that a satisfied predicate no longer ends the call, changes nothing those
 * tests assert — they still get the bytes they expect, just later.
 *
 * Measured: each of the three removals — the substitution, the loop that
 * completes a split character, and the early return — leaves the whole kqueue
 * suite green at 205 of 205. Hence this.
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
    fun `a character split across reads defers the predicate instead of failing the call`() = runBlocking {
        withTimeout(TEST_BUDGET) {
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
                assertTrue(
                    result.contains(REPLACEMENT),
                    "the partial character decodes to U+FFFD, got: ${result.codes()}",
                )
                assertFalse(result.endsWith("あ"), "a partial character must not read as the whole one")
            } finally {
                close(writeFd)
                close(readFd)
            }
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
    fun `a character completed by a later read decodes whole`() = runBlocking {
        withTimeout(TEST_BUDGET) {
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
    }

    /**
     * A satisfied predicate ends the call — it does not merely get consulted.
     *
     * Asserting the payload alone cannot say that: `rawReadUpTo`, which has no
     * predicate at all, returns the same string once the peer goes quiet. So the
     * test makes the difference visible in bytes instead of in a clock. More
     * data is written the moment the predicate holds; a call that stops there
     * cannot have read it, and one that keeps going must.
     *
     * That is also the contract the keep-alive caller depends on: bytes after
     * the match belong to whoever reads next, and consuming them would eat the
     * answer to the following request.
     *
     * Measured: with the early return removed — the predicate still consulted,
     * its answer ignored — every test in this module and all 205 in the kqueue
     * suite stayed green, while the class this exists for went from 1 340 ms
     * back to 21 362 ms. Nothing else holds it.
     */
    @Test
    fun `a satisfied predicate ends the read rather than consuming what follows`() = runBlocking {
        withTimeout(TEST_BUDGET) {
            val (readFd, writeFd) = newSocketPair()
            try {
                writeBytes(writeFd, "HEAD\r\n\r\n".encodeToByteArray())

                var wroteTrailer = false
                val result = PosixRawClient.rawReadUntil(readFd, 64, SHORT_TIMEOUT) { soFar ->
                    val done = soFar.endsWith("\r\n\r\n")
                    if (done && !wroteTrailer) {
                        wroteTrailer = true
                        writeBytes(writeFd, "NEXT".encodeToByteArray())
                    }
                    done
                }

                assertEquals("HEAD\r\n\r\n", result, "the call must stop at the match, leaving NEXT unread")
                assertTrue(wroteTrailer, "the fixture must have written the trailer, or it proves nothing")
            } finally {
                close(writeFd)
                close(readFd)
            }
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

        /**
         * Wall clock for the whole test, as the rules require of anything that
         * reads a socket. [SHORT_TIMEOUT] bounds each read, and this bounds the
         * test — it will not interrupt a blocked syscall, but it says out loud
         * that these are meant to finish, which the first draft of this file did
         * not for fifty minutes.
         */
        val TEST_BUDGET = 15.seconds
    }
}
