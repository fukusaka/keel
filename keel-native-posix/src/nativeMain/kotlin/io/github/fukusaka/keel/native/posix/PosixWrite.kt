package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.pipeline.AbstractIoTransport.PendingWrite
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import platform.posix.EAGAIN
import platform.posix.EWOULDBLOCK
import platform.posix.errno
import platform.posix.write
import posix_socket.keel_writev

/**
 * Outcome of a single POSIX `write(2)` / `writev(2)` invocation.
 *
 * Each engine's `flush` path interprets these three cases:
 *
 * - [Written] — the kernel accepted some bytes. The caller compares the
 *   returned count against the submitted length to detect a partial write
 *   and drives the appropriate re-enqueue / release logic.
 * - [WouldBlock] — the send buffer was full. The caller registers a
 *   write-readiness callback (EPOLLOUT / EVFILT_WRITE) and resumes later.
 * - [Failed] — any other `errno`. The caller releases the write buffers
 *   and propagates the failure as per engine policy (typically closing
 *   the connection).
 */
sealed class WriteResult {
    data class Written(val bytes: Int) : WriteResult()
    data object WouldBlock : WriteResult()
    data class Failed(val errno: Int) : WriteResult()
}

/**
 * Writes up to [length] bytes from [ptr] to [fd] via POSIX `write(2)`.
 *
 * Callers (`EpollIoTransport.flushSingle` / `KqueueIoTransport.flushSingle`)
 * previously inlined the `write` call, `errno` check, and `EAGAIN` /
 * `EWOULDBLOCK` translation. Centralising here ensures every engine treats
 * "would block" and "hard failure" consistently: the sealed [WriteResult]
 * forces every call site to handle all three branches exhaustively at
 * compile time.
 */
@OptIn(ExperimentalForeignApi::class)
fun writeSingle(fd: Int, ptr: CPointer<ByteVar>, length: Int): WriteResult {
    val n = write(fd, ptr, length.convert<ULong>())
    return if (n >= 0) {
        WriteResult.Written(n.toInt())
    } else {
        val err = errno
        if (err == EAGAIN || err == EWOULDBLOCK) WriteResult.WouldBlock
        else WriteResult.Failed(err)
    }
}

/**
 * Gather-writes every [PendingWrite] in [writes] to [fd] via the C
 * `keel_writev` wrapper (POSIX `writev(2)`).
 *
 * Each buffer is submitted from its
 * [unsafePointer][io.github.fukusaka.keel.buf.unsafePointer] + `offset`
 * with length `length`; the caller supplies non-zero-length entries only.
 *
 * Callers (`EpollIoTransport.flushGather` / `KqueueIoTransport.flushGather`)
 * previously duplicated the iovec array preparation, `keel_writev`
 * invocation, and `errno` interpretation. Centralising the syscall
 * wrapper leaves only the engine-specific post-processing (partial-write
 * re-enqueue, `registerWriteCallback` for `WouldBlock`, buffer release
 * for `Failed`) in the engine IoTransport — the POSIX-level concerns
 * (`iovec` layout, `ULongVar` / `CPointerVar` cinterop dance, `errno`
 * semantics) stay in `keel-native-posix`.
 */
@OptIn(ExperimentalForeignApi::class)
fun writeGather(fd: Int, writes: List<PendingWrite>): WriteResult = memScoped {
    val count = writes.size
    val bases = allocArray<CPointerVar<ByteVar>>(count)
    val lens = allocArray<ULongVar>(count)
    for (i in writes.indices) {
        val pw = writes[i]
        bases[i] = (pw.buf.unsafePointer + pw.offset)!!
        lens[i] = pw.length.convert()
    }
    val n = keel_writev(fd, bases.reinterpret(), lens.reinterpret(), count)
    if (n >= 0) {
        WriteResult.Written(n.toInt())
    } else {
        val err = errno
        if (err == EAGAIN || err == EWOULDBLOCK) WriteResult.WouldBlock
        else WriteResult.Failed(err)
    }
}
