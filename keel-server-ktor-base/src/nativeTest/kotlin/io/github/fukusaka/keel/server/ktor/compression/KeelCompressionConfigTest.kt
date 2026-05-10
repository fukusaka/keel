/*
 * Copyright 2026 fukusaka. Licensed under the Apache License, Version 2.0.
 */
package io.github.fukusaka.keel.server.ktor.compression

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
}
