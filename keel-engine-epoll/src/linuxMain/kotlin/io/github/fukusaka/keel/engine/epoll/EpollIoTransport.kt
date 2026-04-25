package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.NativeSocket
import io.github.fukusaka.keel.native.posix.PosixNativeSocket
import io.github.fukusaka.keel.native.posix.ReadResult
import io.github.fukusaka.keel.native.posix.ShutdownResult
import io.github.fukusaka.keel.native.posix.WriteResult
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.native.posix.errnoMessage
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import io.github.fukusaka.keel.pipeline.AbstractIoTransport.PendingWrite
import io.github.fukusaka.keel.pipeline.IoTransport
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.plus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import platform.posix.SHUT_WR
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume

/**
 * epoll [IoTransport] implementation for Linux.
 *
 * **Read path**: registers EPOLLIN via [EpollEventLoop.registerCallback].
 * On data arrival, allocates a buffer, calls POSIX `read()`, and delivers
 * via [onRead]. EAGAIN triggers automatic re-arm.
 *
 * **Write path**: buffers outbound [IoBuf] writes and flushes via POSIX
 * `write()` / `writev()`. On EAGAIN, registers EPOLLOUT and retries.
 *
 * **Thread safety**: all methods must be called on the [eventLoop] thread.
 */
@OptIn(ExperimentalForeignApi::class)
internal class EpollIoTransport(
    private val fd: Int,
    private val eventLoop: EpollEventLoop,
    allocator: BufferAllocator,
    private val nativeSocket: NativeSocket = PosixNativeSocket,
) : AbstractIoTransport(allocator), EpollEventLoop.FdReadyListener {

    /**
     * [EpollEventLoop.FdReadyListener] dispatch — passing `this` to
     * [EpollEventLoop.registerCallback] avoids per-call lambda allocation on
     * the read re-arm fast path. Branch on [interest] is a single enum
     * compare (negligible vs. surrounding syscall + buffer alloc).
     */
    override fun onReady(interest: EpollEventLoop.Interest) {
        when (interest) {
            EpollEventLoop.Interest.READ -> onReadable()
            EpollEventLoop.Interest.WRITE -> onWritable()
        }
    }

    override val ioDispatcher: CoroutineDispatcher get() = eventLoop

    // Parallel primitive arrays reused across [flushGather] calls to
    // feed [NativeSocket.writev] without per-flush heap allocation.
    // Grown lazily (1.5x) via [ensureWritevCapacity] when pendingWrites
    // exceeds the current capacity.
    private var writevPtrs: LongArray = LongArray(INITIAL_WRITEV_CAPACITY)
    private var writevLens: IntArray = IntArray(INITIAL_WRITEV_CAPACITY)

    private fun ensureWritevCapacity(n: Int) {
        if (writevPtrs.size >= n) return
        val grown = maxOf(writevPtrs.size + (writevPtrs.size shr 1), n)
        writevPtrs = LongArray(grown)
        writevLens = IntArray(grown)
    }

    // --- Read path ---

    override var readEnabled: Boolean = false
        set(value) {
            field = value
            if (value && opened) armRead()
        }

    private fun armRead() {
        if (!opened) return
        eventLoop.registerCallback(fd, EpollEventLoop.Interest.READ, this)
    }

    private fun onReadable() {
        if (!opened) return
        val buf = allocator.allocate(IoTransport.DEFAULT_READ_BUFFER_SIZE)
        val ptr = (buf.unsafePointer + buf.writerIndex)!!
        when (val result = nativeSocket.read(fd, ptr, buf.writableBytes)) {
            is ReadResult.Bytes -> {
                buf.writerIndex += result.bytes
                onRead?.invoke(buf)
                armRead()
            }
            ReadResult.Eof -> {
                buf.release()
                onReadClosed?.invoke()
            }
            ReadResult.WouldBlock -> {
                // Spurious wake-up (read readiness without data) — re-arm.
                buf.release()
                armRead()
            }
            is ReadResult.Failed -> {
                // Fatal read error (ECONNRESET / EBADF / ...). EINTR is
                // already absorbed by Layer 1.
                eventLoop.logger.warn { "read failed: fd=$fd ${errnoMessage(result.errno)}" }
                buf.release()
                onReadClosed?.invoke()
            }
        }
    }

    // --- Lifecycle ---

    private var outputShutdown = false

    override fun shutdownOutput() {
        if (!outputShutdown && opened) {
            outputShutdown = true
            when (val result = nativeSocket.shutdown(fd, SHUT_WR)) {
                ShutdownResult.Ok -> Unit
                is ShutdownResult.Failed -> eventLoop.logger.warn {
                    "shutdown(SHUT_WR) failed: fd=$fd ${errnoMessage(result.errno)}"
                }
            }
        }
    }

    // --- Write path ---

    override fun flush(): Boolean {
        if (pendingWrites.isEmpty()) return true
        if (pendingWrites.size == 1) {
            return flushSingle(pendingWrites.removeFirst())
        }
        return flushGather()
    }

    /**
     * Releases all pending write buffers, unregisters from epoll, and
     * closes the socket fd. Idempotent and thread-safe.
     *
     * A non-EventLoop caller dispatches the teardown onto the owning
     * [eventLoop] so the `pendingWrites` / `pendingBytes` mutations and
     * the `eventLoop.cleanupFd` / `close(fd)` pair stay serialised with
     * the read / write / flush paths on the EventLoop thread.
     */
    override fun close() {
        if (!markClosing()) return
        if (eventLoop.inEventLoop()) {
            teardownOnEventLoop()
        } else {
            eventLoop.dispatch(EmptyCoroutineContext, Runnable { teardownOnEventLoop() })
        }
    }

    private fun teardownOnEventLoop() {
        if (!markTeardownStarted()) return
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
        pendingBytes = 0
        eventLoop.cleanupFd(fd)
        closeFdSafely(fd, eventLoop.logger, "transport teardown")
    }

    /**
     * Writes a single [PendingWrite] via POSIX `write()`.
     *
     * On EAGAIN, re-enqueues the remainder and registers EPOLLOUT
     * callback for async retry.
     */
    private fun flushSingle(pw: PendingWrite): Boolean {
        var written = 0
        while (written < pw.length) {
            val ptr = (pw.buf.unsafePointer + pw.offset + written)!!
            when (val result = nativeSocket.write(fd, ptr, pw.length - written)) {
                is WriteResult.Written -> written += result.bytes
                WriteResult.WouldBlock -> {
                    val remainder = PendingWrite(pw.buf, pw.offset + written, pw.length - written)
                    pendingWrites.add(0, remainder)
                    updatePendingBytes(-written)
                    registerWriteCallback()
                    return false
                }
                is WriteResult.Failed -> {
                    eventLoop.logger.warn { "write() failed: fd=$fd ${errnoMessage(result.errno)}" }
                    pw.buf.release()
                    updatePendingBytes(-pw.length)
                    return true
                }
            }
        }
        pw.buf.release()
        updatePendingBytes(-pw.length)
        return true
    }

    /**
     * Writes multiple pending buffers via `writev()` (gather write).
     *
     * On partial write, fully-written buffers are released and the remainder
     * is re-enqueued with EPOLLOUT callback for async retry.
     *
     * Uses the pre-allocated [writevPtrs] / [writevLens] parallel primitive
     * arrays to hand pointers and lengths to [NativeSocket.writev] without
     * allocating a per-flush `List<NativeRegion>`.
     */
    private fun flushGather(): Boolean {
        val count = pendingWrites.size
        ensureWritevCapacity(count)
        var totalBytes = 0
        for (i in 0 until count) {
            val pw = pendingWrites[i]
            writevPtrs[i] = (pw.buf.unsafePointer + pw.offset)!!.rawValue.toLong()
            writevLens[i] = pw.length
            totalBytes += pw.length
        }
        val writtenBytes: Int = when (val result = nativeSocket.writev(fd, writevPtrs, writevLens, count)) {
            WriteResult.WouldBlock -> {
                registerWriteCallback()
                return false
            }
            is WriteResult.Failed -> {
                eventLoop.logger.warn { "writev() failed: fd=$fd ${errnoMessage(result.errno)}" }
                for (pw in pendingWrites) pw.buf.release()
                pendingWrites.clear()
                updatePendingBytes(-totalBytes)
                return true
            }
            is WriteResult.Written -> result.bytes
        }

        if (writtenBytes >= totalBytes) {
            for (pw in pendingWrites) pw.buf.release()
            pendingWrites.clear()
            updatePendingBytes(-totalBytes)
            return true
        }

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

    private var flushContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    /** Registers EPOLLOUT callback on the EventLoop to retry flush when the socket becomes writable. */
    private fun registerWriteCallback() {
        eventLoop.registerCallback(fd, EpollEventLoop.Interest.WRITE, this)
    }

    /** EPOLLOUT callback body — invoked via [onReady] when [EpollEventLoop.Interest.WRITE] fires. */
    private fun onWritable() {
        val done = flush()
        if (done) {
            flushContinuation?.let { cont ->
                flushContinuation = null
                cont.resume(Unit)
            }
            onFlushComplete?.invoke()
        }
    }

    override suspend fun awaitPendingFlush() {
        if (pendingWrites.isEmpty()) return
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            flushContinuation = cont
            cont.invokeOnCancellation { flushContinuation = null }
        }
    }

    private companion object {
        /**
         * Starting capacity of the [writevPtrs] / [writevLens] scratch
         * arrays. Chosen to cover typical gather-write sizes without
         * resizing while staying below a single 64 B cache line worth
         * of Long slots. Grown 1.5x on demand.
         */
        const val INITIAL_WRITEV_CAPACITY = 8
    }
}
