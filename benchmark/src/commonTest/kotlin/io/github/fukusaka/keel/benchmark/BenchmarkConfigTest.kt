package io.github.fukusaka.keel.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CLI parsing + summary/display invariants for [BenchmarkConfig].
 *
 * Bench-harness CLI flags ship without a typed parser library, so a
 * regression in the hand-written `when (key)` block can silently turn
 * `--compression=true` into "unknown engine arg" and cascade to a
 * disabled feature on every engine. These tests pin the parser on
 * each new top-level boolean flag.
 */
class BenchmarkConfigTest {

    @Test
    fun `default config has compression off`() {
        val config = BenchmarkConfig.parse(emptyArray())
        assertFalse(config.compression)
    }

    @Test
    fun `compression flag parses true`() {
        val config = BenchmarkConfig.parse(arrayOf("--compression=true"))
        assertTrue(config.compression)
    }

    @Test
    fun `compression flag parses false explicitly`() {
        val config = BenchmarkConfig.parse(arrayOf("--compression=false"))
        assertFalse(config.compression)
    }

    @Test
    fun `compression flag survives copy round trip`() {
        val original = BenchmarkConfig.parse(arrayOf("--compression=true"))
        val copy = original.copy(port = 9090)
        assertTrue(copy.compression)
        assertEquals(9090, copy.port)
    }

    @Test
    fun `summary includes compression marker when enabled`() {
        val config = BenchmarkConfig.parse(arrayOf("--compression=true"))
        assertTrue(config.summary().contains("compression=on"))
    }

    @Test
    fun `summary omits compression marker when disabled`() {
        val config = BenchmarkConfig.parse(emptyArray())
        assertFalse(config.summary().contains("compression="))
    }

    @Test
    fun `display shows enabled compression line`() {
        val config = BenchmarkConfig.parse(arrayOf("--compression=true"))
        assertTrue(config.display().contains("compression:"))
        assertTrue(config.display().contains("enabled"))
    }

    @Test
    fun `display shows disabled compression line by default`() {
        val config = BenchmarkConfig.parse(emptyArray())
        assertTrue(config.display().contains("compression:"))
        assertTrue(config.display().contains("disabled"))
    }

    @Test
    fun `compression flag composes with other flags`() {
        val config = BenchmarkConfig.parse(
            arrayOf(
                "--compression=true",
                "--connection-close=true",
                "--port=9000",
            ),
        )
        assertTrue(config.compression)
        assertTrue(config.connectionClose)
        assertEquals(9000, config.port)
    }
}
