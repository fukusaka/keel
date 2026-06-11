package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.IdleReadPolicy
import io.netty.channel.embedded.EmbeddedChannel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the flow-control pause contract ([IoTransport.pauseReads]) at the
 * Netty transport seam: the pause must turn auto-read off regardless of
 * [IdleReadPolicy] (under the default `DETECT_PEER_CLOSE` flipping
 * `readEnabled = false` deliberately does not), the read-arming paths
 * must not re-enable auto-read while paused, and resume must restore the
 * policy's steady state. [EmbeddedChannel] exposes the auto-read state
 * synchronously, so no timeouts are needed (pure-synchronous tests).
 */
class NettyIoTransportPauseReadsTest {

    private fun transportOn(channel: EmbeddedChannel, policy: IdleReadPolicy): NettyIoTransport =
        NettyIoTransport(channel, DefaultAllocator, policy)

    @Test
    fun `pause turns auto-read off and resume restores it under DETECT_PEER_CLOSE`() {
        val ch = EmbeddedChannel()
        val transport = transportOn(ch, IdleReadPolicy.DETECT_PEER_CLOSE)
        transport.onChannelAttached() // DETECT arms auto-read at wire-up
        assertTrue(ch.config().isAutoRead)

        transport.pauseReads()
        assertFalse(ch.config().isAutoRead, "the pause must stop Netty's read loop even under DETECT_PEER_CLOSE")

        transport.resumeReads()
        assertTrue(ch.config().isAutoRead, "DETECT's steady state keeps auto-read armed")
    }

    @Test
    fun `arming paths cannot re-enable auto-read while paused`() {
        val ch = EmbeddedChannel()
        val transport = transportOn(ch, IdleReadPolicy.DETECT_PEER_CLOSE)
        transport.onChannelAttached()
        transport.pauseReads()

        // Both arming entry points — the readEnabled setter and the
        // DETECT wire-up hook — funnel through armRead, which is a no-op
        // while paused; otherwise either call would defeat the pause.
        transport.readEnabled = true
        transport.onChannelAttached()
        assertFalse(ch.config().isAutoRead, "armRead must be a no-op while paused")

        transport.resumeReads()
        assertTrue(ch.config().isAutoRead)
    }

    @Test
    fun `resume under PRESERVE_BACKPRESSURE arms only while reads are enabled`() {
        val ch = EmbeddedChannel()
        val transport = transportOn(ch, IdleReadPolicy.PRESERVE_BACKPRESSURE)
        transport.onChannelAttached() // PRESERVE leaves auto-read alone at wire-up
        ch.config().isAutoRead = false // PRESERVE starts disarmed

        transport.pauseReads()
        transport.resumeReads()
        assertFalse(ch.config().isAutoRead, "PRESERVE with readEnabled=false stays disarmed after resume")

        transport.readEnabled = true
        transport.pauseReads()
        assertFalse(ch.config().isAutoRead)
        transport.resumeReads()
        assertTrue(ch.config().isAutoRead, "PRESERVE with readEnabled=true re-arms on resume")
    }
}
