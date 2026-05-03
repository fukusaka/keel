package io.github.fukusaka.keel.engine.kqueue

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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.plus
import platform.posix.SHUT_WR

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
    private val nativeSocket: NativeSocket = PosixNativeSocket,
) : AbstractIoTransport(allocator), KqueueEventLoop.FdReadyListener {

    /**
     * [KqueueEventLoop.FdReadyListener] dispatch — passing `this` to
     * [KqueueEventLoop.registerCallback] avoids per-call lambda allocation
     * on the read re-arm fast path. Branch on [interest] is a single enum
     * compare (negligible vs. surrounding syscall + buffer alloc).
     */
    override fun onReady(interest: KqueueEventLoop.Interest) {
        when (interest) {
            KqueueEventLoop.Interest.READ -> onReadable()
            KqueueEventLoop.Interest.WRITE -> onWritable()
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
        eventLoop.registerCallback(fd, KqueueEventLoop.Interest.READ, this)
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
                buf.release()
                armRead()
            }
            is ReadResult.Failed -> {
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

    /**
     * Attempts to send all pending writes via POSIX `write()`.
     *
     * @return `true` if all data was sent synchronously, `false` if EAGAIN
     *         was encountered and an async EVFILT_WRITE callback is pending.
     */
    override fun flush(): Boolean {
        if (pendingWrites.isEmpty()) return true
        flushCount++
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
     * callbacks check [isOpen] and become no-ops). Idempotent and
     * thread-safe: a non-EventLoop caller dispatches the teardown onto
     * the owning [eventLoop] so the `pendingWrites` / `pendingBytes`
     * mutations stay serialised with the read / write / flush paths.
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
        // Unblock any caller suspended in awaitPendingFlush(): the data is gone.
        flushContinuation?.let { cont ->
            flushContinuation = null
            cont.cancel()
        }
        closeFdSafely(fd, eventLoop.logger, "transport teardown")
        logTransportStatsOnClose(eventLoop.logger, "fd=$fd")
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
            when (val result = nativeSocket.write(fd, ptr, pw.length - written)) {
                is WriteResult.Written -> written += result.bytes
                WriteResult.WouldBlock -> {
                    if (written > 0) partialWriteCount++
                    // Defer remainder: re-enqueue partial PendingWrite and register WRITE interest.
                    val remainder = PendingWrite(pw.buf, pw.offset + written, pw.length - written)
                    pendingWrites.addFirst(remainder)
                    updatePendingBytes(-written)
                    registerWriteCallback()
                    return false
                }
                is WriteResult.Failed -> {
                    // Other error (EPIPE, ECONNRESET) — log, release and drop.
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

    // --- Gather-write flush ---

    /**
     * Writes multiple pending buffers via `writev()`. Falls back to
     * single-buffer retry on partial write or EAGAIN.
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
                // Nothing written — register WRITE and retry all later.
                registerWriteCallback()
                return false
            }
            is WriteResult.Failed -> {
                // Other error — log, release all and return.
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

        // Partial writev: release fully-written buffers, adjust the split buffer.
        // Drain fully-written entries from the head of the deque, mutate
        // the partially-written entry in place at the head, leave the rest.
        // Eliminates the per-partial-write `mutableListOf<PendingWrite>()`
        // + Iterator allocations that the old rebuild-and-replace path
        // required, and reduces the `PendingWrite` allocations to one
        // (only the partial entry — trailing untouched entries stay as-is).
        partialWriteCount++
        var consumed = 0
        while (pendingWrites.isNotEmpty()) {
            val pw = pendingWrites.first()
            if (consumed + pw.length <= writtenBytes) {
                consumed += pw.length
                pw.buf.release()
                pendingWrites.removeFirst()
            } else {
                val alreadyWritten = (writtenBytes - consumed).coerceAtLeast(0)
                pendingWrites[0] = PendingWrite(pw.buf, pw.offset + alreadyWritten, pw.length - alreadyWritten)
                break
            }
        }
        updatePendingBytes(-writtenBytes)
        registerWriteCallback()
        return false
    }

    // --- Async write readiness ---

    private var flushContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    private fun registerWriteCallback() {
        eventLoop.registerCallback(fd, KqueueEventLoop.Interest.WRITE, this)
    }

    /** EVFILT_WRITE callback body — invoked via [onReady] when [KqueueEventLoop.Interest.WRITE] fires. */
    private fun onWritable() {
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

    /**
     * Suspends until all pending async flush operations complete.
     *
     * Returns immediately if no async flush is pending (pendingWrites is empty).
     * Called from Coroutine mode's [KqueuePipelinedChannel.awaitFlushComplete].
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

    private companion object {
        /**
         * Starting capacity of the [writevPtrs] / [writevLens] scratch
         * arrays. Grown 1.5x on demand.
         */
        const val INITIAL_WRITEV_CAPACITY = 8
    }
}
