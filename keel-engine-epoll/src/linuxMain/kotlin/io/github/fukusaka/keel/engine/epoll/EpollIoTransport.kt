package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.native.posix.AbstractPosixIoTransport
import io.github.fukusaka.keel.native.posix.InternalPosixEventLoopApi
import io.github.fukusaka.keel.native.posix.NativeSocket
import io.github.fukusaka.keel.native.posix.PosixNativeSocket
import io.github.fukusaka.keel.pipeline.IoTransport

/**
 * epoll [IoTransport] for a connected socket.
 *
 * Everything this transport does is [AbstractPosixIoTransport]'s: the two
 * engines' versions of it were 1,138 and 1,155 lines that differed in the one
 * line below. What is left here is that line and the engine's own loop type.
 */
@OptIn(InternalPosixEventLoopApi::class)
internal class EpollIoTransport(
    fd: Int,
    private val epollLoop: EpollEventLoop,
    allocator: BufferAllocator,
    nativeSocket: NativeSocket = PosixNativeSocket,
    readBufferSize: Int = IoTransport.DEFAULT_READ_BUFFER_SIZE,
    idleTimeoutMillis: Long = 0,
) : AbstractPosixIoTransport(fd, epollLoop, allocator, nativeSocket, readBufferSize, idleTimeoutMillis) {

    /**
     * epoll keeps its own interest table beside the kernel's, so a closed fd
     * has to be dropped from it explicitly.
     *
     * Left behind, that entry makes the loop treat a recycled fd number as
     * already registered and skip the `epoll_ctl` for it, leaving the next
     * connection on that number watched by nobody. kqueue needs no counterpart:
     * a filter dies with the descriptor it names.
     */
    override fun withdrawFdFromLoop(fd: Int) {
        epollLoop.cleanupFd(fd)
    }
}
