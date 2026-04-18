package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import java.net.InetSocketAddress as JavaInetSocketAddress

/**
 * Netty channel supporting both Pipeline mode and Coroutine mode.
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
        /** Extracts keel [SocketAddress] from a [java.net.SocketAddress]. */
        internal fun toSocketAddress(addr: java.net.SocketAddress?): SocketAddress? = when (addr) {
            is JavaInetSocketAddress -> InetSocketAddress(addr.address.hostAddress, addr.port)
            is java.net.UnixDomainSocketAddress -> io.github.fukusaka.keel.core.UnixSocketAddress(addr.path.toString())
            else -> null
        }
    }
}
