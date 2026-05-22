package io.github.fukusaka.keel.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Validation of the per-bind / per-connect `readBufferSize` override on
 * [BindConfig] and [ConnectConfig]. `null` (default) inherits the engine-wide
 * [IoEngineConfig.readBufferSize]; a non-null value must satisfy the same
 * power-of-two / range invariant as [IoEngineConfig.readBufferSize].
 */
class ReadBufferSizeOverrideTest {

    @Test
    fun `BindConfig read buffer size defaults to null so it inherits the engine value`() {
        assertNull(BindConfig().readBufferSize)
    }

    @Test
    fun `ConnectConfig read buffer size defaults to null so it inherits the engine value`() {
        assertNull(ConnectConfig().readBufferSize)
    }

    @Test
    fun `BindConfig accepts a valid power-of-two override`() {
        assertEquals(16384, BindConfig(readBufferSize = 16384).readBufferSize)
    }

    @Test
    fun `ConnectConfig accepts a valid power-of-two override`() {
        assertEquals(4096, ConnectConfig(readBufferSize = 4096).readBufferSize)
    }

    @Test
    fun `BindConfig rejects a non power-of-two override`() {
        assertFailsWith<IllegalArgumentException> { BindConfig(readBufferSize = 10000) }
    }

    @Test
    fun `ConnectConfig rejects an out-of-range override`() {
        assertFailsWith<IllegalArgumentException> {
            ConnectConfig(readBufferSize = IoEngineConfig.MAX_READ_BUFFER_SIZE * 2)
        }
    }
}
