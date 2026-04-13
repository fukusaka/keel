package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel

/**
 * NIO channel supporting both Pipeline mode and Coroutine mode.
 *
 * All I/O logic (read, write, flush, shutdown, close) is delegated to
 * [NioIoTransport] via [AbstractPipelinedChannel].
 */
internal class NioPipelinedChannel(
    transport: NioIoTransport,
    logger: Logger,
    remoteAddress: SocketAddress? = null,
    localAddress: SocketAddress? = null,
) : AbstractPipelinedChannel(transport, logger, remoteAddress, localAddress) {

    companion object {
        /**
         * Parses a [java.net.SocketAddress] into a keel [SocketAddress].
         * Returns null if the address type is not supported.
         */
        fun toSocketAddress(addr: java.net.SocketAddress?): SocketAddress? {
            return when (addr) {
                is java.net.InetSocketAddress -> SocketAddress(
                    addr.address?.hostAddress ?: addr.hostString,
                    addr.port,
                )
                else -> null
            }
        }
    }
}
