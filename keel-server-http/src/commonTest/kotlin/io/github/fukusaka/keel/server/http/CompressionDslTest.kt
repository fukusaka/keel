package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpRequestDecompressionHandler
import io.github.fukusaka.keel.codec.http.UnknownEncodingPolicy
import io.github.fukusaka.keel.compression.CompressionRegistry
import io.github.fukusaka.keel.compression.DeflateTuning
import io.github.fukusaka.keel.compression.Strategy
import io.github.fukusaka.keel.server.http.dsl.CompressionBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the `compression { … }` DSL block — the build outputs
 * a [CompressionPipelineConfig] only when at least one encoder or a
 * `requestDecompression { }` block was supplied, and the surface
 * properties round-trip through to the resolved config.
 *
 * The actual `CompressionHandler` / `HttpRequestDecompressionHandler`
 * behaviours are covered in `:keel-codec-http` — these tests pin the
 * DSL-to-config wiring only.
 */
class CompressionDslTest {

    @Test
    fun `empty block returns null so install pass adds no handler`() {
        val cfg = CompressionBuilder().apply { /* no encoder, no requestDecompression */ }.build()
        assertNull(cfg)
    }

    @Test
    fun `encoder registers in registry and flips hasResponseEncoder`() {
        val cfg = CompressionBuilder().apply {
            encoder(FakeCodec("gzip"), priority = 1)
        }.build()
        assertNotNull(cfg)
        assertTrue(cfg.hasResponseEncoder)
        assertNotNull(cfg.registry.findEncoder("gzip"))
        // request decompression branch is independent and stays null when not configured.
        assertNull(cfg.requestDecompression)
    }

    @Test
    fun `requestDecompression alone leaves hasResponseEncoder false but still configures inbound`() {
        val cfg = CompressionBuilder().apply {
            requestDecompression {
                limit = 5L * 1024 * 1024
                ratioLimit = 50
                ratioBurst = 7
                unknownEncoding = UnknownEncodingPolicy.Passthrough
            }
        }.build()
        assertNotNull(cfg)
        assertFalse(cfg.hasResponseEncoder)
        val req = cfg.requestDecompression
        assertNotNull(req)
        assertEquals(5L * 1024 * 1024, req.limit)
        assertEquals(50, req.ratioLimit)
        assertEquals(7, req.ratioBurst)
        assertEquals(UnknownEncodingPolicy.Passthrough, req.unknownEncoding)
    }

    @Test
    fun `requestDecompression defaults match HttpRequestDecompressionHandler constants`() {
        val cfg = CompressionBuilder().apply {
            requestDecompression { /* defaults */ }
        }.build()
        val req = cfg!!.requestDecompression!!
        assertEquals(HttpRequestDecompressionHandler.DEFAULT_DECOMPRESSION_LIMIT, req.limit)
        assertEquals(HttpRequestDecompressionHandler.DEFAULT_RATIO_LIMIT, req.ratioLimit)
        assertEquals(HttpRequestDecompressionHandler.DEFAULT_RATIO_BURST, req.ratioBurst)
        assertEquals(UnknownEncodingPolicy.UnsupportedMediaType, req.unknownEncoding)
    }

    @Test
    fun `deflate block sets the encoder DeflateTuning forwarded to the handler`() {
        val cfg = CompressionBuilder().apply {
            encoder(FakeCodec("gzip"))
            deflate {
                windowBits = 12
                strategy = Strategy.HuffmanOnly
            }
        }.build()
        val tuning = cfg!!.encoderOptions.tuning as DeflateTuning
        assertEquals(12, tuning.windowBits)
        assertEquals(Strategy.HuffmanOnly, tuning.strategy)
    }

    @Test
    fun `without a deflate block the encoder options carry no tuning`() {
        val cfg = CompressionBuilder().apply {
            encoder(FakeCodec("gzip"))
        }.build()
        assertNull(cfg!!.encoderOptions.tuning)
    }

    @Test
    fun `responseCondition minContentLength threshold gates compression on small responses`() {
        val cfg = CompressionBuilder().apply {
            encoder(FakeCodec("gzip"))
            responseCondition {
                minContentLength = 1024
            }
        }.build()
        // Build a fake head below + above the threshold and assert the condition
        // matches the configured cutoff. shouldCompress(...) is the unit-of-truth
        // owned by CompressionCondition; the DSL only ferries the value through.
        val below = io.github.fukusaka.keel.codec.http.HttpResponseHead(
            status = io.github.fukusaka.keel.codec.http.HttpStatus.OK,
            headers = io.github.fukusaka.keel.codec.http.HttpHeaders.of(
                "Content-Type" to "text/plain",
                "Content-Length" to "512",
            ),
        )
        val above = io.github.fukusaka.keel.codec.http.HttpResponseHead(
            status = io.github.fukusaka.keel.codec.http.HttpStatus.OK,
            headers = io.github.fukusaka.keel.codec.http.HttpHeaders.of(
                "Content-Type" to "text/plain",
                "Content-Length" to "2048",
            ),
        )
        assertFalse(cfg!!.responseCondition.shouldCompress(below))
        assertTrue(cfg.responseCondition.shouldCompress(above))
    }

    @Test
    fun `excludeContentTypePrefix is additive on top of the built-in exclusions`() {
        val cfg = CompressionBuilder().apply {
            encoder(FakeCodec("gzip"))
            responseCondition {
                excludeContentTypePrefix("application/vnd.custom+binary")
            }
        }.build()
        // built-in exclusion still applies (image/* should be skipped)
        val image = io.github.fukusaka.keel.codec.http.HttpResponseHead(
            status = io.github.fukusaka.keel.codec.http.HttpStatus.OK,
            headers = io.github.fukusaka.keel.codec.http.HttpHeaders.of(
                "Content-Type" to "image/png",
            ),
        )
        // new exclusion applies
        val custom = io.github.fukusaka.keel.codec.http.HttpResponseHead(
            status = io.github.fukusaka.keel.codec.http.HttpStatus.OK,
            headers = io.github.fukusaka.keel.codec.http.HttpHeaders.of(
                "Content-Type" to "application/vnd.custom+binary",
            ),
        )
        // unrelated text/plain still passes
        val text = io.github.fukusaka.keel.codec.http.HttpResponseHead(
            status = io.github.fukusaka.keel.codec.http.HttpStatus.OK,
            headers = io.github.fukusaka.keel.codec.http.HttpHeaders.of(
                "Content-Type" to "text/plain",
            ),
        )
        assertFalse(cfg!!.responseCondition.shouldCompress(image))
        assertFalse(cfg.responseCondition.shouldCompress(custom))
        assertTrue(cfg.responseCondition.shouldCompress(text))
    }

    @Test
    fun `replaceContentTypeExclusions wipes the built-in list`() {
        val cfg = CompressionBuilder().apply {
            encoder(FakeCodec("gzip"))
            responseCondition {
                replaceContentTypeExclusions("application/octet-stream")
            }
        }.build()
        // image/* used to be excluded by default; after replace, it's compressible
        val image = io.github.fukusaka.keel.codec.http.HttpResponseHead(
            status = io.github.fukusaka.keel.codec.http.HttpStatus.OK,
            headers = io.github.fukusaka.keel.codec.http.HttpHeaders.of(
                "Content-Type" to "image/png",
            ),
        )
        assertTrue(cfg!!.responseCondition.shouldCompress(image))
        // application/octet-stream is now the only exclusion
        val binary = io.github.fukusaka.keel.codec.http.HttpResponseHead(
            status = io.github.fukusaka.keel.codec.http.HttpStatus.OK,
            headers = io.github.fukusaka.keel.codec.http.HttpHeaders.of(
                "Content-Type" to "application/octet-stream",
            ),
        )
        assertFalse(cfg.responseCondition.shouldCompress(binary))
    }

    @Test
    fun `custom responseCondition predicate runs after the built-in checks`() {
        val cfg = CompressionBuilder().apply {
            encoder(FakeCodec("gzip"))
            responseCondition {
                custom { head -> head.headers["X-No-Compress"] == null }
            }
        }.build()
        val allow = io.github.fukusaka.keel.codec.http.HttpResponseHead(
            status = io.github.fukusaka.keel.codec.http.HttpStatus.OK,
            headers = io.github.fukusaka.keel.codec.http.HttpHeaders.of(
                "Content-Type" to "text/plain",
            ),
        )
        val deny = io.github.fukusaka.keel.codec.http.HttpResponseHead(
            status = io.github.fukusaka.keel.codec.http.HttpStatus.OK,
            headers = io.github.fukusaka.keel.codec.http.HttpHeaders.of(
                "Content-Type" to "text/plain",
                "X-No-Compress" to "1",
            ),
        )
        assertTrue(cfg!!.responseCondition.shouldCompress(allow))
        assertFalse(cfg.responseCondition.shouldCompress(deny))
    }
}

/** Minimal [io.github.fukusaka.keel.compression.CompressionCodec] stub. */
private class FakeCodec(override val name: String) : io.github.fukusaka.keel.compression.CompressionCodec {
    override val encoder: io.github.fukusaka.keel.compression.Encoder =
        object : io.github.fukusaka.keel.compression.Encoder {
            override val name: String = this@FakeCodec.name
            override fun newSession(
                allocator: io.github.fukusaka.keel.buf.BufferAllocator,
                options: io.github.fukusaka.keel.compression.EncoderOptions,
            ): io.github.fukusaka.keel.compression.EncoderSession =
                throw UnsupportedOperationException("not needed for DSL tests")
        }
    override val decoder: io.github.fukusaka.keel.compression.Decoder =
        object : io.github.fukusaka.keel.compression.Decoder {
            override val name: String = this@FakeCodec.name
            override fun newSession(
                allocator: io.github.fukusaka.keel.buf.BufferAllocator,
                options: io.github.fukusaka.keel.compression.DecoderOptions,
            ): io.github.fukusaka.keel.compression.DecoderSession =
                throw UnsupportedOperationException("not needed for DSL tests")
        }
}

// Sanity reference so the registry import stays in scope after refactors.
@Suppress("unused")
private fun keepImport(r: CompressionRegistry) = r
