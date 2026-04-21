package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.pipeline.AbstractIoTransport.PendingWrite
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.plus

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
 * Writes up to [length] bytes from [ptr] to [fd].
 *
 * Delegates to [NativeSocket.write] on [PosixNativeSocket], which
 * routes through the Layer 1 `keel_write` cinterop wrapper — EINTR
 * is retried transparently, so the returned [WriteResult] never
 * represents "interrupted mid-syscall" as a spurious failure. The
 * Sealed-result shape (`Written` / `WouldBlock` / `Failed`) matches
 * `NativeSocket.write` directly.
 *
 * Retained as a free function for backwards compatibility with
 * existing `EpollIoTransport.flushSingle` / `KqueueIoTransport.flushSingle`
 * call sites. New code should prefer [PosixNativeSocket.write] directly.
 */
@OptIn(ExperimentalForeignApi::class)
fun writeSingle(fd: Int, ptr: CPointer<ByteVar>, length: Int): WriteResult =
    PosixNativeSocket.write(fd, ptr, length)

/**
 * Gather-writes every [PendingWrite] in [writes] to [fd].
 *
 * Delegates to [NativeSocket.writev] on [PosixNativeSocket] (Layer 1
 * `keel_writev` cinterop wrapper with EINTR retry).
 *
 * Retained as a free function for backwards compatibility with
 * existing `EpollIoTransport.flushGather` / `KqueueIoTransport.flushGather`
 * call sites. New code should prefer [PosixNativeSocket.writev] directly.
 */
@OptIn(ExperimentalForeignApi::class)
fun writeGather(fd: Int, writes: List<PendingWrite>): WriteResult {
    val regions = writes.map { pw ->
        NativeRegion((pw.buf.unsafePointer + pw.offset)!!, pw.length)
    }
    return PosixNativeSocket.writev(fd, regions)
}
