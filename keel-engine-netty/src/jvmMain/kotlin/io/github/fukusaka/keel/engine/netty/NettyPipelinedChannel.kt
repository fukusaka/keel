package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import java.net.InetSocketAddress

/**
 * Netty channel supporting both Pipeline mode and Channel mode.
 *
 * All I/O logic (read, write, flush, shutdown, close) is delegated to
 * [NettyIoTransport] via [AbstractPipelinedChannel].
 */
class NettyPipelinedChannel internal constructor(
    transport: NettyIoTransport,
    logger: Logger,
    remoteAddress: SocketAddress? = null,
    localAddress: SocketAddress? = null,
) : AbstractPipelinedChannel(transport, logger, remoteAddress, localAddress) {

    companion object {
        /** Extracts [SocketAddress] from a Java NIO [InetSocketAddress]. */
        internal fun toSocketAddress(addr: java.net.SocketAddress?): SocketAddress? {
            val inet = addr as? InetSocketAddress ?: return null
            return SocketAddress(inet.address.hostAddress, inet.port)
        }
    }
}
