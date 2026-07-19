package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.codec.http.Http1ServerCodec
import io.github.fukusaka.keel.codec.http.addHttp1ServerCodec
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * The WebSocket upgrade strips the HTTP codec stack by name before installing
 * the WS codec. If that list misses a stage the server codec can install, the
 * stage survives onto the WebSocket pipeline — and the deadline stages
 * force-close the channel when a request deadline elapses, with no
 * `HttpBodyEnd` able to disarm them once the decoder is gone. This pins the
 * list to the full set of stages `addHttp1ServerCodec` can install.
 */
class WebSocketUpgradeCodecRemovalTest {

    @Test
    fun `the removal list covers every stage the server codec can install`() {
        val channel = object : AbstractPipelinedChannel(TestIoTransport(), PrintLogger("ws-upgrade-test")) {}
        // A connector that configures every optional guard installs the full stack.
        channel.addHttp1ServerCodec(
            headerTimeoutMillis = 1_000,
            requestTimeoutMillis = 5_000,
            minBodyRateBytesPerSec = 1,
        )

        // Mirror the upgrade's removal loop.
        for (name in HTTP_CODEC_HANDLER_NAMES) {
            runCatching { channel.pipeline.remove(name) }
        }

        assertNull(channel.pipeline.get(Http1ServerCodec.DECODER), "decoder stage removed")
        assertNull(channel.pipeline.get(Http1ServerCodec.ENCODER), "encoder stage removed")
        assertNull(channel.pipeline.get(Http1ServerCodec.AGGREGATOR), "aggregator stage removed")
        assertNull(
            channel.pipeline.get(Http1ServerCodec.REQUEST_DEADLINE),
            "request-deadline stage removed — it force-closes the channel on elapse",
        )
        assertNull(
            channel.pipeline.get(Http1ServerCodec.BODY_RATE_FLOOR),
            "body-rate-floor stage removed — it force-closes the channel on elapse",
        )
    }
}
