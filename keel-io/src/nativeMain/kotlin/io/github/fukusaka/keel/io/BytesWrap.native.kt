package io.github.fukusaka.keel.io

import io.github.fukusaka.keel.buf.IoBuf

/**
 * Native: returns `null` — no zero-copy `ByteArray` wrapping is available
 * yet. Pinning a `ByteArray` for the duration of an async write requires
 * tracking the [kotlinx.cinterop.Pinned] handle across the event-loop
 * boundary, which is not implemented. Callers fall back to the chunked
 * copy path in [BufferedSuspendSink]. This is acceptable because Native
 * engines (kqueue/epoll/io-uring/nwconnection) submit buffers via native
 * iovec arrays without going through a per-chunk `ByteBuf` allocation, so
 * they do not suffer from the GC-driven variance that motivates the direct
 * path on JVM.
 */
internal actual fun wrapBytesAsIoBuf(bytes: ByteArray, offset: Int, length: Int): IoBuf? = null
