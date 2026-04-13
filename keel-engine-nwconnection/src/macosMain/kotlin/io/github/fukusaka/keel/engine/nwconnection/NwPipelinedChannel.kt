package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel

/**
 * NWConnection channel supporting both Pipeline mode and Coroutine mode.
 *
 * All I/O logic (read, write, flush, shutdown, close) is delegated to
 * [NwIoTransport] via [AbstractPipelinedChannel].
 */
internal class NwPipelinedChannel(
    transport: NwIoTransport,
    logger: Logger,
    remoteAddress: SocketAddress? = null,
    localAddress: SocketAddress? = null,
) : AbstractPipelinedChannel(transport, logger, remoteAddress, localAddress)
