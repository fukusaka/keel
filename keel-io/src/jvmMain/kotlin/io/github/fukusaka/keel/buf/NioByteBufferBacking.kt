package io.github.fukusaka.keel.buf

import java.nio.ByteBuffer

/**
 * Implemented by JVM [IoBuf] types that can expose a writable NIO
 * [ByteBuffer] view over their full backing memory.
 *
 * The view covers the entire [IoBuf.capacity] range (indices 0 ..
 * capacity - 1). Callers are responsible for setting [ByteBuffer.position]
 * and [ByteBuffer.limit] before each use.
 *
 * Used by JSSE TLS codec ([io.github.fukusaka.keel.tls.jsse.JsseTlsCodec])
 * to pass buffer memory directly to [javax.net.ssl.SSLEngine.wrap] /
 * [javax.net.ssl.SSLEngine.unwrap] without allocating intermediate arrays.
 */
interface NioByteBufferBacking {
    /**
     * A writable [ByteBuffer] whose backing store covers the full
     * [IoBuf.capacity] range. Position and limit are unspecified —
     * the caller must configure them before each use.
     *
     * **Capacity**: the view covers [0, capacity) fixed at construction.
     * Implementations do not resize the backing buffer after allocation, so
     * the range remains stable for the lifetime of the [IoBuf].
     *
     * **Lifetime**: valid only while the owning [IoBuf]'s refcount is greater
     * than zero. Accessing this buffer after the [IoBuf] is released is
     * undefined behaviour (use-after-free for off-heap implementations).
     */
    @UnsafeIoBufApi
    val unsafeNioByteBuffer: ByteBuffer
}
