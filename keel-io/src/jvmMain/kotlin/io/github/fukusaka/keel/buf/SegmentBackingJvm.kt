package io.github.fukusaka.keel.buf

import java.nio.ByteBuffer

/**
 * Returns the underlying direct [ByteBuffer] for the JVM backing.
 *
 * Used by engines (`engine-nio`, `engine-netty`) to construct
 * scatter-gather descriptors (`SocketChannel.write(ByteBuffer[])`) over
 * multi-segment [IoBuf] chains without copying.
 *
 * The returned buffer is the *original* writable direct ByteBuffer
 * shared with the backing — callers must not mutate its
 * `position` / `limit` / `mark` permanently. To select a window for I/O,
 * use [ByteBuffer.duplicate] (or `slice`) and set position/limit on the
 * duplicate.
 *
 * @throws ClassCastException if the backing is not a JVM direct backing
 *   — currently impossible (only [DirectByteBufferBacking] implements
 *   [SegmentBacking] on JVM), but reserved for a future shared-memory
 *   carrier that would expose a different platform handle.
 */
public fun SegmentBacking.asByteBuffer(): ByteBuffer =
    (this as DirectByteBufferBacking).base
