package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.server.TlsServerConfig
import io.github.fukusaka.keel.tls.TlsConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Verifies [NodeEngine]'s [io.github.fukusaka.keel.server.ServerTlsProvider]
 * implementation backing the `connector { }` `EngineNative` TLS strategy.
 */
class NodeEngineServerTlsProviderTest {

    @Test
    fun `nativeTlsBindConfig yields a listener-level TlsServerConfig`() {
        val engine = NodeEngine()
        val socketOptions = SocketOptions(tcpNoDelay = false)
        val config = engine.nativeTlsBindConfig(TlsConfig(), backlog = 64, socketOptions = socketOptions)

        assertIs<TlsServerConfig>(config)
        // null installer => the engine handles TLS at the tls.createServer() level.
        assertNull(config.installer)
        assertEquals(64, config.backlog)
        assertEquals(socketOptions, config.childSocketOptions)
    }
}
