package io.github.fukusaka.keel.server

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.core.StreamServer
import io.github.fukusaka.keel.server.dsl.connector
import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.TlsCodecFactory
import io.github.fukusaka.keel.tls.TlsConfig
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Minimal [StreamEngine] with no native TLS — for resolution tests. */
private open class FakeEngine : StreamEngine {
    override val coroutineContext: CoroutineContext = EmptyCoroutineContext
    override val config: IoEngineConfig = IoEngineConfig()
    override suspend fun close() {}
    override suspend fun bind(address: SocketAddress, bindConfig: BindConfig): StreamServer =
        throw UnsupportedOperationException()
    override suspend fun connect(address: SocketAddress): Channel =
        throw UnsupportedOperationException()
}

/** [StreamEngine] that advertises native server TLS via [ServerTlsProvider]. */
private class FakeNativeTlsEngine : FakeEngine(), ServerTlsProvider {
    override fun nativeTlsBindConfig(
        tls: TlsConfig,
        backlog: Int,
        socketOptions: SocketOptions,
    ): BindConfig = NativeTlsMarkerConfig(backlog, socketOptions)
}

/** Marker [BindConfig] so a test can confirm the native path was taken. */
private class NativeTlsMarkerConfig(
    backlog: Int,
    socketOptions: SocketOptions,
) : BindConfig(backlog, socketOptions)

/** [TlsCodecFactory] stub — never invoked, only identity-checked. */
private class FakeTlsCodecFactory : TlsCodecFactory {
    override fun createServerCodec(config: TlsConfig): TlsCodec = error("not used")
    override fun createClientCodec(config: TlsConfig): TlsCodec = error("not used")
    override fun close() {}
}

class ServerConnectorTest {

    @Test
    fun `connector DSL applies host port backlog and defaults to plain TCP`() {
        val c = connector {
            host = "127.0.0.1"
            port = 8443
            backlog = 256
        }
        assertEquals("127.0.0.1", c.host)
        assertEquals(8443, c.port)
        assertEquals(256, c.backlog)
        assertNull(c.tls)
    }

    @Test
    fun `connector defaults match ServerConnector companion`() {
        val c = connector {}
        assertEquals(ServerConnector.DEFAULT_HOST, c.host)
        assertEquals(ServerConnector.DEFAULT_PORT, c.port)
        assertEquals(BindConfig.DEFAULT_BACKLOG, c.backlog)
    }

    @Test
    fun `tls block without a config fails fast`() {
        assertFailsWith<IllegalStateException> {
            connector { tls { strategy = ServerTlsStrategy.EngineNative } }
        }
    }

    @Test
    fun `tls block without a strategy fails fast`() {
        assertFailsWith<IllegalStateException> {
            connector { tls { config = TlsConfig() } }
        }
    }

    @Test
    fun `resolveBindConfig without TLS yields a plain BindConfig`() {
        val opts = SocketOptions(tcpNoDelay = false)
        val c = connector {
            backlog = 64
            socketOptions = opts
        }
        val config = c.resolveBindConfig(FakeEngine())
        assertEquals(BindConfig::class, config::class)
        assertEquals(64, config.backlog)
        assertEquals(opts, config.childSocketOptions)
    }

    @Test
    fun `resolveBindConfig EngineNative delegates to a ServerTlsProvider engine`() {
        val opts = SocketOptions(tcpNoDelay = false)
        val c = connector {
            backlog = 99
            socketOptions = opts
            tls {
                config = TlsConfig()
                strategy = ServerTlsStrategy.EngineNative
            }
        }
        val config = c.resolveBindConfig(FakeNativeTlsEngine())
        assertIs<NativeTlsMarkerConfig>(config)
        assertEquals(99, config.backlog)
        assertEquals(opts, config.childSocketOptions)
    }

    @Test
    fun `resolveBindConfig EngineNative rejects an engine without native TLS`() {
        val c = connector {
            tls {
                config = TlsConfig()
                strategy = ServerTlsStrategy.EngineNative
            }
        }
        val ex = assertFailsWith<IllegalStateException> { c.resolveBindConfig(FakeEngine()) }
        assertTrue(ex.message?.contains("KeelCodec") == true)
    }

    @Test
    fun `resolveBindConfig KeelCodec yields a TlsServerConfig with a codec installer`() {
        val factory = FakeTlsCodecFactory()
        val opts = SocketOptions(tcpNoDelay = false)
        val c = connector {
            backlog = 32
            socketOptions = opts
            tls {
                config = TlsConfig()
                strategy = ServerTlsStrategy.KeelCodec(factory)
            }
        }
        val config = c.resolveBindConfig(FakeEngine())
        assertIs<TlsServerConfig>(config)
        assertIs<TlsCodecServerInstaller>(config.installer)
        assertEquals(32, config.backlog)
        assertEquals(opts, config.childSocketOptions)
    }

    @Test
    fun `resolveBindConfig Custom yields a TlsServerConfig with the supplied installer`() {
        val installer = TlsServerInstaller { _, _ -> }
        val c = connector {
            tls {
                config = TlsConfig()
                strategy = ServerTlsStrategy.Custom(installer)
            }
        }
        val config = c.resolveBindConfig(FakeEngine())
        assertIs<TlsServerConfig>(config)
        assertSame(installer, config.installer)
    }

    @Test
    fun `address reflects host and port`() {
        val c = connector {
            host = "127.0.0.1"
            port = 8080
        }
        assertEquals("127.0.0.1:8080", c.address.toString())
    }
}
