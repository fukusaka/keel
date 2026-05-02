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
     */
    val unsafeNioByteBuffer: ByteBuffer
}
