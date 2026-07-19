package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The stage-name constants ([Http1ClientCodec] / [Http1ServerCodec]) are the
 * public contract for the handlers [addHttp1ClientCodec] / [addHttp1ServerCodec]
 * install. These tests pin the constants to what is actually installed, so a
 * custom handler positioned with `addBefore` / `addAfter` targets a real stage.
 */
class HttpCodecStageNamesTest {

    private fun channel(name: String) =
        object : AbstractPipelinedChannel(TestIoTransport(), PrintLogger(name)) {}

    private object Probe : InboundHandler

    @Test
    fun `client codec installs the stages named by Http1ClientCodec`() {
        val channel = channel("client")
        channel.addHttp1ClientCodec()
        assertNotNull(channel.pipeline.get(Http1ClientCodec.ENCODER), "encoder stage")
        assertNotNull(channel.pipeline.get(Http1ClientCodec.DECODER), "decoder stage")
        assertNotNull(channel.pipeline.get(Http1ClientCodec.AGGREGATOR), "aggregator stage")
    }

    @Test
    fun `client aggregator stage is absent when aggregateBody is false`() {
        val channel = channel("client")
        channel.addHttp1ClientCodec(aggregateBody = false)
        assertNotNull(channel.pipeline.get(Http1ClientCodec.DECODER))
        assertNull(channel.pipeline.get(Http1ClientCodec.AGGREGATOR), "aggregator omitted when disabled")
    }

    @Test
    fun `a custom handler inserts before the client decoder stage`() {
        val channel = channel("client")
        channel.addHttp1ClientCodec()
        channel.pipeline.addBefore(Http1ClientCodec.DECODER, "probe", Probe)
        assertSame(Probe, channel.pipeline.get("probe"), "constant is a valid insertion anchor")
    }

    @Test
    fun `server codec installs the stages named by Http1ServerCodec`() {
        val channel = channel("server")
        channel.addHttp1ServerCodec()
        assertNotNull(channel.pipeline.get(Http1ServerCodec.DECODER), "decoder stage")
        assertNotNull(channel.pipeline.get(Http1ServerCodec.ENCODER), "encoder stage")
        assertNotNull(channel.pipeline.get(Http1ServerCodec.AGGREGATOR), "aggregator stage")
    }

    @Test
    fun `server deadline and body-rate stages appear only when configured`() {
        val plain = channel("server-plain")
        plain.addHttp1ServerCodec()
        assertNull(plain.pipeline.get(Http1ServerCodec.REQUEST_DEADLINE), "no deadline stage by default")
        assertNull(plain.pipeline.get(Http1ServerCodec.BODY_RATE_FLOOR), "no body-rate stage by default")

        val guarded = channel("server-guarded")
        guarded.addHttp1ServerCodec(
            headerTimeoutMillis = 1_000,
            requestTimeoutMillis = 5_000,
            minBodyRateBytesPerSec = 1,
        )
        assertNotNull(guarded.pipeline.get(Http1ServerCodec.REQUEST_DEADLINE), "deadline stage when configured")
        assertNotNull(guarded.pipeline.get(Http1ServerCodec.BODY_RATE_FLOOR), "body-rate stage when configured")
    }
}
