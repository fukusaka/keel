@file:OptIn(InternalPosixEventLoopApi::class)

package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.native.posix.InternalPosixEventLoopApi
import io.github.fukusaka.keel.native.posix.PosixIoTransport
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel

/**
 * kqueue channel supporting both Pipeline mode and Coroutine mode.
 *
 * All I/O logic (read, write, flush, shutdown, close) is delegated to
 * [PosixIoTransport] via [AbstractPipelinedChannel].
 */
internal class KqueuePipelinedChannel(
    transport: PosixIoTransport,
    logger: Logger,
    remoteAddress: SocketAddress? = null,
    localAddress: SocketAddress? = null,
) : AbstractPipelinedChannel(transport, logger, remoteAddress, localAddress)
