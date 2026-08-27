package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.core.Host
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.core.SocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the interface's default derivations, which every engine without its
 * own override serves to callers: [PipelinedStreamServer.activeLocalAddresses]
 * follows [PipelinedStreamServer.isActive] — every bound address while
 * listening, none once closed — and [PipelinedStreamServer.localAddresses]
 * is the single bound address unless overridden.
 *
 * Synchronous property reads on a hand-rolled double; no timeout needed.
 */
class PipelinedStreamServerDefaultsTest {

    private class DefaultingServer(
        override val localAddress: SocketAddress,
        override var isActive: Boolean,
    ) : PipelinedStreamServer {
        override fun close() {
            isActive = false
        }
    }

    private val address = InetSocketAddress(Host.Ip(IpAddress.parse("127.0.0.1")), 18300)

    @Test
    fun `an active server without an override reports every bound address as accepting`() {
        val server = DefaultingServer(address, isActive = true)

        assertEquals(listOf(address), server.localAddresses)
        assertEquals(server.localAddresses, server.activeLocalAddresses)
    }

    @Test
    fun `a closed server without an override reports no address as accepting`() {
        val server = DefaultingServer(address, isActive = true)
        server.close()

        assertEquals(listOf(address), server.localAddresses, "the bind-order list is history, not liveness")
        assertTrue(server.activeLocalAddresses.isEmpty(), "got: ${server.activeLocalAddresses}")
    }
}
