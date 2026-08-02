package io.github.fukusaka.keel.server

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.server.dsl.connector
import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.TlsCodecFactory
import io.github.fukusaka.keel.tls.TlsCodecResult
import io.github.fukusaka.keel.tls.TlsConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Direct tests for the `connector { }` / `tls { }` DSL builders that
 * complement [ServerConnectorTest]'s integration-flavoured checks. Focuses
 * on default value parity, error messages for missing required fields,
 * overwrite-on-second-call behaviour, and SocketOptions propagation.
 */
class ConnectorDslTest {

    private class StubFactory : TlsCodecFactory {
        override fun createServerCodec(config: TlsConfig): TlsCodec = StubCodec()
        override fun createClientCodec(config: TlsConfig): TlsCodec = StubCodec()
        override fun close() {}
    }

    private class StubCodec : TlsCodec {
        override val isHandshakeComplete: Boolean = false
        override val negotiatedProtocol: String? = null
        override val peerCertificates: List<ByteArray> = emptyList()
        override fun protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult = error("not used")
        override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult = error("not used")
        override fun close() {}
    }

    @Test
    fun `empty block produces all-default ServerConnector`() {
        val c = connector {}
        assertEquals(ServerConnector.DEFAULT_HOST, c.host)
        assertEquals(ServerConnector.DEFAULT_PORT, c.port)
        assertEquals(BindConfig.DEFAULT_BACKLOG, c.backlog)
        assertEquals(SocketOptions.DEFAULT, c.socketOptions)
        assertNull(c.tls)
    }

    @Test
    fun `socketOptions override is propagated to ServerConnector`() {
        val opts = SocketOptions(tcpNoDelay = false)
        val c = connector { socketOptions = opts }
        assertSame(opts, c.socketOptions)
    }

    @Test
    fun `tls block populates ServerTls with both fields`() {
        val tlsConfig = TlsConfig()
        val factory = StubFactory()
        val c = connector {
            tls {
                config = tlsConfig
                strategy = ServerTlsStrategy.KeelCodec(factory)
            }
        }

        val tls = c.tls
        assertNotNull(tls)
        assertSame(tlsConfig, tls.config)
        val strategy = tls.strategy
        assertIs<ServerTlsStrategy.KeelCodec>(strategy)
        assertSame(factory, strategy.factory)
    }

    @Test
    fun `calling tls block twice keeps the latest values`() {
        val firstFactory = StubFactory()
        val secondFactory = StubFactory()
        val c = connector {
            tls {
                config = TlsConfig()
                strategy = ServerTlsStrategy.KeelCodec(firstFactory)
            }
            tls {
                config = TlsConfig()
                strategy = ServerTlsStrategy.KeelCodec(secondFactory)
            }
        }

        val strategy = c.tls?.strategy
        assertIs<ServerTlsStrategy.KeelCodec>(strategy)
        assertSame(secondFactory, strategy.factory, "second tls block must overwrite the first")
    }

    @Test
    fun `missing tls config produces an actionable IllegalStateException message`() {
        val ex = assertFailsWith<IllegalStateException> {
            connector { tls { strategy = ServerTlsStrategy.EngineNative } }
        }
        val msg = ex.message
        assertNotNull(msg)
        // Hint must steer the caller to the config field.
        assertTrue(msg.contains("config"), "expected message to mention `config`, got: $msg")
    }

    @Test
    fun `missing tls strategy produces an actionable IllegalStateException message`() {
        val ex = assertFailsWith<IllegalStateException> {
            connector { tls { config = TlsConfig() } }
        }
        val msg = ex.message
        assertNotNull(msg)
        assertTrue(msg.contains("strategy"), "expected message to mention `strategy`, got: $msg")
    }

    @Test
    fun `large port number is accepted as-is by the DSL`() {
        // Port-range validation is the engine's responsibility — the DSL only
        // stores the int — but the upper bound 65535 is a useful boundary pin.
        val c = connector { port = 65_535 }
        assertEquals(65_535, c.port)
    }

    @Test
    fun `port zero is the default and round-trips through the DSL`() {
        val c = connector { port = 0 }
        assertEquals(0, c.port)
        assertEquals(ServerConnector.DEFAULT_PORT, c.port)
    }

    @Test
    fun `backlog override is propagated to ServerConnector and matches BindConfig default`() {
        // Default parity: the builder picks up BindConfig.DEFAULT_BACKLOG, so a
        // bump in BindConfig flows through automatically.
        val defaultC = connector {}
        assertEquals(BindConfig.DEFAULT_BACKLOG, defaultC.backlog)

        val overridden = connector { backlog = 512 }
        assertEquals(512, overridden.backlog)
    }
}
