package io.github.fukusaka.keel.native.posix

import platform.posix.EAGAIN
import platform.posix.EBADF
import platform.posix.ECONNRESET
import platform.posix.EINVAL
import platform.posix.ENOBUFS
import platform.posix.ENOMEM
import platform.posix.EPIPE
import platform.posix.EWOULDBLOCK
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins which write outcomes are retryable, because everything above this
 * treats the answer as final: a `Failed` ends the connection's write side,
 * and no caller re-classifies it.
 */
class WriteReturnClassificationTest {

    @Test
    fun `a positive return is what was written`() {
        assertEquals(WriteResult.Written(7), classifyWriteReturn(7, 0))
    }

    @Test
    fun `a zero return for a non-empty request is definitive`() {
        // TCP write(2) does not do this; treating it as progress would loop
        // on a write that moves nothing.
        assertEquals(WriteResult.Failed(0), classifyWriteReturn(0, 0))
    }

    @Test
    fun `the blocked errnos are retryable`() {
        assertEquals(WriteResult.WouldBlock, classifyWriteReturn(-1, EAGAIN))
        assertEquals(WriteResult.WouldBlock, classifyWriteReturn(-1, EWOULDBLOCK))
    }

    @Test
    fun `a kernel out of buffer space is retryable rather than a dead socket`() {
        // The load that produces ENOBUFS is the load worth surviving; the
        // send succeeds once space frees, which write readiness waits for.
        assertEquals(WriteResult.WouldBlock, classifyWriteReturn(-1, ENOBUFS))
    }

    @Test
    fun `a peer that is gone is definitive`() {
        assertEquals(WriteResult.Failed(EPIPE), classifyWriteReturn(-1, EPIPE))
        assertEquals(WriteResult.Failed(ECONNRESET), classifyWriteReturn(-1, ECONNRESET))
    }

    @Test
    fun `our own errors are definitive`() {
        assertEquals(WriteResult.Failed(EBADF), classifyWriteReturn(-1, EBADF))
        assertEquals(WriteResult.Failed(EINVAL), classifyWriteReturn(-1, EINVAL))
    }

    @Test
    fun `out of memory stays definitive`() {
        // Not scoped to socket buffer space, so it carries no promise that
        // waiting for write readiness would help.
        assertEquals(WriteResult.Failed(ENOMEM), classifyWriteReturn(-1, ENOMEM))
    }
}
