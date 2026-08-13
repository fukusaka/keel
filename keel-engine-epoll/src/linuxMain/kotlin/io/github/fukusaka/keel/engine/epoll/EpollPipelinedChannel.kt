@file:OptIn(InternalPosixEventLoopApi::class)

package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.native.posix.InternalPosixEventLoopApi
import io.github.fukusaka.keel.native.posix.PosixIoTransport
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel

/**
 * epoll channel supporting both Pipeline mode and Coroutine mode.
 *
 * All I/O logic (read, write, flush, shutdown, close) is delegated to
 * [PosixIoTransport] via [AbstractPipelinedChannel].
 */
internal class EpollPipelinedChannel(
    transport: PosixIoTransport,
    logger: Logger,
    remoteAddress: SocketAddress? = null,
    localAddress: SocketAddress? = null,
) : AbstractPipelinedChannel(transport, logger, remoteAddress, localAddress)
