package io.github.fukusaka.keel.testing.engine

import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel

/**
 * In-memory channel for one half of an [InMemoryEngine] connection.
 *
 * All I/O (read, write, flush, shutdown, close) is delegated to an
 * [InMemoryIoTransport] via [AbstractPipelinedChannel]. The same class
 * backs both ends of a connection:
 *
 * - the **client** side, returned from [InMemoryEngine.connect] and used
 *   in Coroutine mode (`read` / `write` via the lazy `SuspendBridgeHandler`),
 * - the **server** side, on which the listener's `pipelineInitializer`
 *   installs its handler stack.
 */
internal class InMemoryPipelinedChannel(
    transport: InMemoryIoTransport,
    logger: Logger,
    remoteAddress: SocketAddress? = null,
    localAddress: SocketAddress? = null,
) : AbstractPipelinedChannel(transport, logger, remoteAddress, localAddress)
