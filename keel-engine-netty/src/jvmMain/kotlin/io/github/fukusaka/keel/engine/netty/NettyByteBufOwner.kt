package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.IoBufMemoryOwner
import io.netty.buffer.ByteBuf

/**
 * [IoBufMemoryOwner] that releases a Netty [ByteBuf] at refcount zero.
 *
 * Used by the zero-copy inbound path in [NettyIoTransport]: when the
 * kernel delivers a single-backing [ByteBuf], the transport wraps the
 * [ByteBuf]'s [nioBuffer][ByteBuf.nioBuffer] view as a keel `DirectIoBuf`
 * and installs this owner. The [ByteBuf] is kept alive in Netty's pool
 * until the wrapped `IoBuf` reaches refcount zero, at which point
 * [release] forwards to [ByteBuf.release].
 *
 * **Thread safety**: [ByteBuf.release] is thread-safe (atomic CAS), but
 * the wrapped `IoBuf` has non-atomic refcount semantics and must be
 * confined to the owning EventLoop.
 */
internal class NettyByteBufOwner(private val byteBuf: ByteBuf) : IoBufMemoryOwner {
    override fun release(buf: IoBuf) {
        byteBuf.release()
    }
}
