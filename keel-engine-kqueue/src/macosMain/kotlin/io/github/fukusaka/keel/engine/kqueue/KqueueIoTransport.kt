package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import io.github.fukusaka.keel.pipeline.AbstractIoTransport.PendingWrite
import io.github.fukusaka.keel.pipeline.IoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import platform.posix.EAGAIN
import platform.posix.EWOULDBLOCK
import platform.posix.SHUT_WR
import platform.posix.close
import platform.posix.errno
import platform.posix.read
import platform.posix.shutdown
import platform.posix.write
import kotlinx.cinterop.ByteVar
import posix_socket.keel_writev

/**
 * kqueue [IoTransport] implementation for macOS.
 *
 * **Read path**: registers EVFILT_READ via [KqueueEventLoop.registerCallback].
 * On data arrival, allocates a buffer, calls POSIX `read()`, and delivers
 * via [onRead]. EAGAIN triggers automatic re-arm.
 *
 * **Write path**: buffers outbound [IoBuf] writes and flushes via POSIX
 * `write()` / `writev()`. On EAGAIN, registers EVFILT_WRITE and retries.
 *
 * **Thread safety**: all methods must be called on the [eventLoop] thread.
 */
@OptIn(ExperimentalForeignApi::class)
internal class KqueueIoTransport(
    private val fd: Int,
    private val eventLoop: KqueueEventLoop,
    allocator: BufferAllocator,
) : AbstractIoTransport(allocator) {

    override val ioDispatcher: CoroutineDispatcher get() = eventLoop

    // --- Read path ---

    override var readEnabled: Boolean = false
        set(value) {
            field = value
            if (value && opened) armRead()
        }

    private fun armRead() {
        if (!opened) return
        eventLoop.registerCallback(fd, KqueueEventLoop.Interest.READ) {
            onReadable()
        }
    }

    private fun onReadable() {
        if (!opened) return
        val buf = allocator.allocate(IoTransport.DEFAULT_READ_BUFFER_SIZE)
        val ptr = (buf.unsafePointer + buf.writerIndex)!!
        val n = read(fd, ptr, buf.writableBytes.convert())
        when {
            n > 0 -> {
                buf.writerIndex += n.toInt()
                onRead?.invoke(buf)
                armRead()
            }
            n == 0L -> {
                buf.release()
                onReadClosed?.invoke()
            }
            else -> {
                val err = errno
                if (err == EAGAIN || err == EWOULDBLOCK) {
                    buf.release()
                    armRead()
                } else {
                    buf.release()
                    onReadClosed?.invoke()
                }
            }
        }
    }

    // --- Lifecycle ---

    private var outputShutdown = false

    override fun shutdownOutput() {
        if (!outputShutdown && opened) {
            outputShutdown = true
            shutdown(fd, SHUT_WR)
        }
    }

    // --- Write path ---

    /**
     * Attempts to send all pending writes via POSIX `write()`.
     *
     * @return `true` if all data was sent synchronously, `false` if EAGAIN
     *         was encountered and an async EVFILT_WRITE callback is pending.
     */
    override fun flush(): Boolean {
        if (pendingWrites.isEmpty()) return true

        if (pendingWrites.size == 1) {
            return flushSingle(pendingWrites.removeFirst())
        }
        return flushGather()
    }

    /**
     * Releases all pending write buffers and closes the socket fd.
     *
     * Unsent data is discarded — no flush is attempted. Does NOT unregister
     * any pending EVFILT_READ/WRITE callbacks from the EventLoop (the
     * callbacks check [isOpen] and become no-ops). Idempotent.
     */
    override fun close() {
        if (!opened) return
        opened = false
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
        pendingBytes = 0
        close(fd)
    }

    // --- Single-buffer flush ---

    /**
     * Writes a single buffer. On EAGAIN, registers EVFILT_WRITE callback
     * to retry with the remaining bytes.
     */
    private fun flushSingle(pw: PendingWrite): Boolean {
        var written = 0
        while (written < pw.length) {
            val ptr = (pw.buf.unsafePointer + pw.offset + written)!!
            val remaining = (pw.length - written).convert<ULong>()
            val n = write(fd, ptr, remaining)
            if (n >= 0) {
                written += n.toInt()
            } else {
                val err = errno
                if (err == EAGAIN || err == EWOULDBLOCK) {
                    // Defer remainder: re-enqueue partial PendingWrite and register WRITE interest.
                    val remainder = PendingWrite(pw.buf, pw.offset + written, pw.length - written)
                    pendingWrites.add(0, remainder)
                    updatePendingBytes(-written)
                    registerWriteCallback()
                    return false
                }
                // Other error (EPIPE, ECONNRESET) — release and drop.
                pw.buf.release()
                updatePendingBytes(-pw.length)
                return true
            }
        }
        pw.buf.release()
        updatePendingBytes(-pw.length)
        return true
    }

    // --- Gather-write flush ---

    /**
     * Writes multiple pending buffers via `writev()`. Falls back to
     * single-buffer retry on partial write or EAGAIN.
     */
    private fun flushGather(): Boolean {
        val totalBytes = pendingWrites.sumOf { it.length }
        val writtenBytes: Int

        memScoped {
            val count = pendingWrites.size
            val bases = allocArray<CPointerVar<ByteVar>>(count)
            val lens = allocArray<ULongVar>(count)
            for ((i, pw) in pendingWrites.withIndex()) {
                bases[i] = (pw.buf.unsafePointer + pw.offset)!!
                lens[i] = pw.length.convert()
            }
            val n = keel_writev(fd, bases.reinterpret(), lens.reinterpret(), count)
            if (n < 0) {
                val err = errno
                if (err == EAGAIN || err == EWOULDBLOCK) {
                    // Nothing written — register WRITE and retry all later.
                    registerWriteCallback()
                    return false
                }
                // Other error — release all and return.
                for (pw in pendingWrites) pw.buf.release()
                pendingWrites.clear()
                updatePendingBytes(-totalBytes)
                return true
            }
            writtenBytes = n.toInt()
        }

        if (writtenBytes >= totalBytes) {
            for (pw in pendingWrites) pw.buf.release()
            pendingWrites.clear()
            updatePendingBytes(-totalBytes)
            return true
        }

        // Partial writev: release fully-written buffers, adjust the split buffer.
        val remaining = mutableListOf<PendingWrite>()
        var consumed = 0
        for (pw in pendingWrites) {
            if (consumed + pw.length <= writtenBytes) {
                consumed += pw.length
                pw.buf.release()
            } else {
                val alreadyWritten = (writtenBytes - consumed).coerceAtLeast(0)
                remaining.add(PendingWrite(pw.buf, pw.offset + alreadyWritten, pw.length - alreadyWritten))
                consumed += pw.length
            }
        }
        pendingWrites.clear()
        pendingWrites.addAll(remaining)
        updatePendingBytes(-writtenBytes)
        registerWriteCallback()
        return false
    }

    // --- Async write readiness ---

    private var flushContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    private fun registerWriteCallback() {
        eventLoop.registerCallback(fd, KqueueEventLoop.Interest.WRITE) {
            // Retry flush when fd becomes writable.
            val done = flush()
            if (done) {
                flushContinuation?.let { cont ->
                    flushContinuation = null
                    cont.resume(Unit)
                }
                onFlushComplete?.invoke()
            }
        }
    }

    /**
     * Suspends until all pending async flush operations complete.
     *
     * Returns immediately if no async flush is pending (pendingWrites is empty).
     * Called from Channel mode's [KqueuePipelinedChannel.awaitFlushComplete].
     *
     * Must be called on the EventLoop thread (no synchronisation needed).
     */
    override suspend fun awaitPendingFlush() {
        if (pendingWrites.isEmpty()) return
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            flushContinuation = cont
            cont.invokeOnCancellation { flushContinuation = null }
        }
    }

}
