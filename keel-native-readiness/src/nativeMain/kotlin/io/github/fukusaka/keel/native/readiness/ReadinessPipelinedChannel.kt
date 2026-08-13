package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel

/**
 * A readiness-loop channel, in Pipeline mode or Coroutine mode.
 *
 * All I/O — read, write, flush, shutdown, close — is [ReadinessIoTransport]'s,
 * through [AbstractPipelinedChannel]. Both engines had this class with nothing
 * in it but the transport's type, and that type is now one.
 *
 * `internal` rather than behind the opt-in marker: every construction site is
 * in this module — the two servers, and the engine's two connect paths — so the
 * compiler can enforce it outright instead of asking anyone to opt in.
 */
@OptIn(InternalReadinessEngineApi::class)
internal class ReadinessPipelinedChannel(
    transport: ReadinessIoTransport,
    logger: Logger,
    remoteAddress: SocketAddress? = null,
    localAddress: SocketAddress? = null,
) : AbstractPipelinedChannel(transport, logger, remoteAddress, localAddress)
