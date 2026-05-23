package io.github.fukusaka.keel.server

import io.github.fukusaka.keel.tls.TlsConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [TlsServerConfig] forwards the per-server `readBufferSize` override to
 * its [io.github.fukusaka.keel.core.BindConfig] ancestor so TLS server
 * callers reach the same per-bind read-buffer-size knob as plain
 * `BindConfig` callers.
 */
class TlsServerConfigTest {

    private val tls = TlsConfig()

    @Test
    fun `read buffer size defaults to null so it inherits the engine value`() {
        assertNull(TlsServerConfig(tls).readBufferSize)
    }

    @Test
    fun `read buffer size override is forwarded to BindConfig`() {
        assertEquals(16384, TlsServerConfig(tls, readBufferSize = 16384).readBufferSize)
    }
}
