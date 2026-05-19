package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.DirectIoBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.Unpooled
import kotlin.test.Test
import kotlin.test.assertEquals

class NettyByteBufOwnerTest {

    @Test
    fun `wrap pooled ByteBuf zero-copy, release decrements Netty refcount`() {
        val pooled = ByteBufAllocator.DEFAULT.directBuffer(64)
        try {
            pooled.writeBytes("HELLO".toByteArray())
            assertEquals(1, pooled.refCnt())

            val readable = pooled.readableBytes()
            val nio = pooled.nioBuffer(pooled.readerIndex(), readable)
            val buf = DirectIoBuf.wrapExternal(
                buffer = nio,
                bytesWritten = readable,
                owner = NettyByteBufOwner(pooled),
            )
            assertEquals(readable, buf.readableBytes)
            assertEquals('H'.code.toByte(), buf.readByte())
            assertEquals('E'.code.toByte(), buf.readByte())

            // Netty refcount still held by the owner.
            assertEquals(1, pooled.refCnt())
            buf.release()
            // Owner forwarded to ByteBuf.release().
            assertEquals(0, pooled.refCnt())
        } finally {
            if (pooled.refCnt() > 0) pooled.release()
        }
    }

    @Test
    fun `retain then release keeps ByteBuf alive until refcount zero`() {
        val pooled = ByteBufAllocator.DEFAULT.directBuffer(16).writeBytes(byteArrayOf(0x01, 0x02))
        try {
            val nio = pooled.nioBuffer(pooled.readerIndex(), pooled.readableBytes())
            val buf = DirectIoBuf.wrapExternal(nio, 2, NettyByteBufOwner(pooled))

            buf.retain()
            buf.release()
            assertEquals(1, pooled.refCnt(), "still alive at refCount=1")
            buf.release()
            assertEquals(0, pooled.refCnt(), "released at refCount=0")
        } finally {
            if (pooled.refCnt() > 0) pooled.release()
        }
    }

    @Test
    fun `close is escape hatch, does NOT release Netty ByteBuf`() {
        val pooled = Unpooled.directBuffer(8).writeBytes(byteArrayOf(0x41))
        try {
            val nio = pooled.nioBuffer(pooled.readerIndex(), pooled.readableBytes())
            val buf = DirectIoBuf.wrapExternal(nio, 1, NettyByteBufOwner(pooled))

            buf.close()
            // Owner skipped; Netty ref still held. Caller responsible for cleanup.
            assertEquals(1, pooled.refCnt())
        } finally {
            pooled.release()
        }
    }
}
