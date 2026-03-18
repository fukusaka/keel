package io.github.keel.engine.kqueue

import io.github.keel.core.BufferAllocator
import io.github.keel.core.Channel
import io.github.keel.core.NativeBuf
import io.github.keel.core.SocketAddress
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.plus
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import platform.posix.EAGAIN
import platform.posix.EWOULDBLOCK
import platform.posix.SHUT_WR
import platform.posix.close
import platform.posix.errno
import platform.posix.read
import platform.posix.shutdown
import platform.posix.write

private class PendingWrite(val buf: NativeBuf, val offset: Int, val length: Int)

@OptIn(ExperimentalForeignApi::class)
internal class KqueueChannel(
    private val fd: Int,
    override val allocator: BufferAllocator,
    override val remoteAddress: SocketAddress?,
    override val localAddress: SocketAddress?,
) : Channel {

    private val pendingWrites = mutableListOf<PendingWrite>()
    private var _open = true
    private var _active = true
    private var outputShutdown = false

    override val isOpen: Boolean get() = _open
    override val isActive: Boolean get() = _active

    override suspend fun awaitClosed() {
        // Phase (a): synchronous — if already closed, return immediately
    }

    override suspend fun read(buf: NativeBuf): Int = readBlocking(buf)

    override suspend fun write(buf: NativeBuf): Int = writeBlocking(buf)

    override suspend fun flush() = flushBlocking()

    internal fun readBlocking(buf: NativeBuf): Int {
        check(_open) { "Channel is closed" }
        val ptr = (buf.unsafePointer + buf.writerIndex)!!
        val n = read(fd, ptr, buf.writableBytes.convert())
        return when {
            n > 0 -> {
                buf.writerIndex += n.toInt()
                n.toInt()
            }
            n == 0L -> -1 // EOF
            else -> {
                val err = errno
                if (err == EAGAIN || err == EWOULDBLOCK) 0
                else -1
            }
        }
    }

    internal fun writeBlocking(buf: NativeBuf): Int {
        check(_open) { "Channel is closed" }
        check(!outputShutdown) { "Output already shut down" }
        val bytes = buf.readableBytes
        if (bytes == 0) return 0
        val offset = buf.readerIndex
        buf.retain()
        buf.readerIndex += bytes
        pendingWrites.add(PendingWrite(buf, offset, bytes))
        return bytes
    }

    internal fun flushBlocking() {
        check(_open) { "Channel is closed" }
        for (pw in pendingWrites) {
            val ptr = (pw.buf.unsafePointer + pw.offset)!!
            write(fd, ptr, pw.length.convert())
            pw.buf.release()
        }
        pendingWrites.clear()
    }

    override fun shutdownOutput() {
        if (!outputShutdown && _open) {
            outputShutdown = true
            shutdown(fd, SHUT_WR)
        }
    }

    override fun asSource(): RawSource = ChannelSource(this, allocator)

    override fun asSink(): RawSink = ChannelSink(this, allocator)

    override fun close() {
        if (_open) {
            _open = false
            _active = false
            for (pw in pendingWrites) {
                pw.buf.release()
            }
            pendingWrites.clear()
            close(fd)
        }
    }
}
