package io.github.fukusaka.keel.io

import io.github.fukusaka.keel.buf.IoBuf

/**
 * Wraps a [ByteArray] range as an [IoBuf] without copying, if the platform supports it.
 *
 * Used by [BufferedSuspendSink] to avoid chunk-by-chunk copying of large response
 * bodies (e.g., Ktor's `respondBytes(byte[])`) through the internal scratch buffer.
 * When non-null, the returned [IoBuf] references the caller's array directly; the
 * caller must not modify the array until the transport has sent it.
 *
 * Platform behaviour:
 * - **JVM**: returns a [io.github.fukusaka.keel.buf.DirectIoBuf] wrapping a
 *   [java.nio.ByteBuffer.wrap] view of the array. The underlying buffer is
 *   heap-backed. NIO's `SocketChannel.write(HeapByteBuffer)` internally copies
 *   to a pool of direct buffers once per syscall, which is still dramatically
 *   faster than Kotlin-side chunked copies.
 * - **Native**: returns `null`. Native engines use `nativeHeap`-backed [IoBuf]
 *   and cannot trivially wrap a `ByteArray` without pinning. Callers fall back
 *   to the copy-based chunked write path.
 * - **JS**: returns `null`. Same reasoning — [BufferedSuspendSink]'s fallback
 *   copies bytes into a platform-native scratch buffer.
 *
 * @param bytes  Source array; caller retains ownership and must not modify
 *               it while the returned buffer is in flight.
 * @param offset Start offset into [bytes].
 * @param length Number of readable bytes.
 * @return A zero-copy [IoBuf] view, or `null` when the platform cannot avoid
 *         copying. The returned buffer has `readerIndex = 0` and
 *         `writerIndex = length`.
 */
internal expect fun wrapBytesAsIoBuf(bytes: ByteArray, offset: Int, length: Int): IoBuf?
