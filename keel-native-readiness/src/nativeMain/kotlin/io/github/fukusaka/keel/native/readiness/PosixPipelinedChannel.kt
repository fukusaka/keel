package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel

/**
 * A readiness-loop channel, in Pipeline mode or Coroutine mode.
 *
 * All I/O — read, write, flush, shutdown, close — is [PosixIoTransport]'s,
 * through [AbstractPipelinedChannel]. Both engines had this class with nothing
 * in it but the transport's type, and that type is now one.
 *
 * Behind the opt-in marker, like the rest of this surface: the engines build it
 * and nobody else should.
 */
@InternalReadinessEngineApi
class PosixPipelinedChannel(
    transport: PosixIoTransport,
    logger: Logger,
    remoteAddress: SocketAddress? = null,
    localAddress: SocketAddress? = null,
) : AbstractPipelinedChannel(transport, logger, remoteAddress, localAddress)
