package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel

/**
 * io_uring channel supporting both Pipeline mode and Coroutine mode.
 *
 * All I/O logic (multishot recv, write/flush, shutdown, close) is delegated
 * to [IoUringIoTransport] via [AbstractPipelinedChannel].
 *
 * @param transport The io_uring transport owning the fd, buffer ring, and multishot state.
 */
internal class IoUringPipelinedChannel(
    private val ioUringTransport: IoUringIoTransport,
    logger: Logger,
    remoteAddress: SocketAddress? = null,
    localAddress: SocketAddress? = null,
) : AbstractPipelinedChannel(ioUringTransport, logger, remoteAddress, localAddress)
