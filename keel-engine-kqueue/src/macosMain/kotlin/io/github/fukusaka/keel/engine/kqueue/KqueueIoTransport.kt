package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.native.posix.AbstractPosixIoTransport
import io.github.fukusaka.keel.native.posix.InternalPosixEventLoopApi
import io.github.fukusaka.keel.native.posix.NativeSocket
import io.github.fukusaka.keel.native.posix.PosixNativeSocket
import io.github.fukusaka.keel.pipeline.IoTransport

/**
 * kqueue [IoTransport] for a connected socket.
 *
 * Everything this transport does is [AbstractPosixIoTransport]'s: the two
 * engines' versions of it were 1,155 and 1,138 lines that differed in one line
 * of code. What is left here is the engine's own type for the loop.
 *
 * kqueue keeps no interest table of its own — a filter dies with the descriptor
 * it names — so the base's fd-withdrawal hook stays a no-op here.
 */
@OptIn(InternalPosixEventLoopApi::class)
internal class KqueueIoTransport(
    fd: Int,
    eventLoop: KqueueEventLoop,
    allocator: BufferAllocator,
    nativeSocket: NativeSocket = PosixNativeSocket,
    readBufferSize: Int = IoTransport.DEFAULT_READ_BUFFER_SIZE,
    idleTimeoutMillis: Long = 0,
) : AbstractPosixIoTransport(fd, eventLoop, allocator, nativeSocket, readBufferSize, idleTimeoutMillis)
