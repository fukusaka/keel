package io.github.fukusaka.keel.server

import io.github.fukusaka.keel.tls.TlsConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * [TlsServerConfig] forwards the per-server `readBufferSize` override to
 * its [io.github.fukusaka.keel.core.BindConfig] ancestor so TLS server
 * callers reach the same per-bind read-buffer-size knob as plain
 * `BindConfig` callers, and exposes the TLS-specific
 * `plaintextBufferSize` override that is forwarded to
 * [TlsServerInstaller] via the three-argument `install` overload.
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

    @Test
    fun `plaintext buffer size defaults to null so it inherits the TlsHandler default`() {
        assertNull(TlsServerConfig(tls).plaintextBufferSize)
    }

    @Test
    fun `plaintext buffer size override at the minimum is accepted`() {
        assertEquals(16384, TlsServerConfig(tls, plaintextBufferSize = 16384).plaintextBufferSize)
    }

    @Test
    fun `plaintext buffer size override above the minimum is accepted`() {
        assertEquals(32768, TlsServerConfig(tls, plaintextBufferSize = 32768).plaintextBufferSize)
    }

    @Test
    fun `plaintext buffer size below 16 KiB is rejected`() {
        assertFailsWith<IllegalArgumentException> { TlsServerConfig(tls, plaintextBufferSize = 8192) }
    }

    @Test
    fun `plaintext buffer size that is not a power of two is rejected`() {
        assertFailsWith<IllegalArgumentException> { TlsServerConfig(tls, plaintextBufferSize = 24576) }
    }
}
