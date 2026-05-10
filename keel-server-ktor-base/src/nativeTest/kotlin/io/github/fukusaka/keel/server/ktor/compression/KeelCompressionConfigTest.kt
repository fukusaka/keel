package io.github.fukusaka.keel.server.ktor.compression

import io.github.fukusaka.keel.codec.http.UnknownEncodingPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Configuration DSL tests for [KeelCompressionConfig]. Validates the
 * builder produces an equivalent registration shape to ktor-server-compression's
 * `CompressionConfig` (gzip + deflate registered, deflate priority 0.9,
 * default conditions injected when no user conditions are present).
 */
class KeelCompressionConfigTest {

    @Test
    fun `default registers gzip and deflate with correct priorities`() {
        val config = KeelCompressionConfig().apply { default() }
        assertTrue("gzip" in config.encoders, "gzip should be registered by default()")
        assertTrue("deflate" in config.encoders, "deflate should be registered by default()")
        assertEquals(1.0, config.encoders["gzip"]!!.priority, "gzip priority")
        assertEquals(0.9, config.encoders["deflate"]!!.priority, "deflate priority — gzip should win ties")
    }

    @Test
    fun `gzip block runs after default priority is applied`() {
        val config = KeelCompressionConfig().apply {
            gzip {
                priority = 2.0
            }
        }
        assertEquals(2.0, config.encoders["gzip"]!!.priority)
    }

    @Test
    fun `deflate block runs after default priority is applied`() {
        val config = KeelCompressionConfig().apply {
            deflate {
                priority = 1.5
            }
        }
        assertEquals(1.5, config.encoders["deflate"]!!.priority)
    }

    @Test
    fun `registering same encoder twice throws`() {
        val config = KeelCompressionConfig()
        config.gzip()
        assertFailsWith<IllegalArgumentException> { config.gzip() }
    }

    @Test
    fun `buildOptions snapshots conditions list`() {
        val config = KeelCompressionConfig().apply {
            default()
            condition { _ -> true } // marker
        }
        val options = config.buildOptions()
        // 1 user-supplied global condition. gzip + deflate each get default conditions
        // injected when they have none of their own.
        assertEquals(1, options.conditions.size)
        assertEquals(2, options.encoders.size)
    }

    @Test
    fun `buildOptions injects default per-encoder conditions when none supplied`() {
        val config = KeelCompressionConfig().apply {
            // No global conditions, no per-encoder conditions — defaults should be injected.
            default()
        }
        val options = config.buildOptions()
        assertEquals(0, options.conditions.size)
        // Default conditions = excludeContentType(...) + minimumSize(...) = 7 conditions per encoder.
        // Mirror of ktor's CompressionConfig.defaultConditions().
        for ((name, encoderConfig) in options.encoders) {
            assertTrue(
                encoderConfig.conditions.isNotEmpty(),
                "$name should have default conditions injected when no user conditions present",
            )
        }
    }

    // ------------------------------------------------------------------ Mode

    @Test
    fun `mode default is All -- both response and request`() {
        val options = KeelCompressionConfig().buildOptions()
        assertEquals(KeelCompressionConfig.Mode.All, options.mode)
        assertTrue(options.mode.response, "default mode should enable response side")
        assertTrue(options.mode.request, "default mode should enable request side")
    }

    @Test
    fun `mode CompressResponse disables request side`() {
        val options = KeelCompressionConfig()
            .apply { mode = KeelCompressionConfig.Mode.CompressResponse }
            .buildOptions()
        assertTrue(options.mode.response, "response side should be enabled")
        assertTrue(!options.mode.request, "request side should be disabled")
    }

    @Test
    fun `mode DecompressRequest disables response side`() {
        val options = KeelCompressionConfig()
            .apply { mode = KeelCompressionConfig.Mode.DecompressRequest }
            .buildOptions()
        assertTrue(!options.mode.response, "response side should be disabled")
        assertTrue(options.mode.request, "request side should be enabled")
    }

    // ------------------------------------------------------------------ inbound limit defaults

    @Test
    fun `inbound limit defaults match the codec-http handler defaults`() {
        // Pin parity with HttpRequestDecompressionHandler defaults so callers
        // configuring one path get matching behaviour from the other.
        val config = KeelCompressionConfig()
        assertEquals(1L * 1024 * 1024, config.decompressionLimit, "1 MiB absolute cap")
        assertEquals(100, config.ratioLimit, "100:1 decoded:input ratio cap")
        assertEquals(3, config.ratioBurst, "burst tolerance 3 (Apache mod_deflate)")
        assertEquals(
            UnknownEncodingPolicy.UnsupportedMediaType,
            config.unknownEncodingPolicy,
            "unknown encoding default → 415",
        )
    }

    @Test
    fun `inbound limit overrides round-trip through buildOptions`() {
        val options = KeelCompressionConfig().apply {
            decompressionLimit = 100L
            ratioLimit = 5
            ratioBurst = 7
            unknownEncodingPolicy = UnknownEncodingPolicy.BadRequest
        }.buildOptions()
        assertEquals(100L, options.decompressionLimit)
        assertEquals(5, options.ratioLimit)
        assertEquals(7, options.ratioBurst)
        assertEquals(UnknownEncodingPolicy.BadRequest, options.unknownEncodingPolicy)
    }

    @Test
    fun `inbound limits opt out via Long_MAX_VALUE and Int_MAX_VALUE`() {
        val options = KeelCompressionConfig().apply {
            decompressionLimit = Long.MAX_VALUE
            ratioLimit = Int.MAX_VALUE
        }.buildOptions()
        assertEquals(Long.MAX_VALUE, options.decompressionLimit)
        assertEquals(Int.MAX_VALUE, options.ratioLimit)
    }
}
