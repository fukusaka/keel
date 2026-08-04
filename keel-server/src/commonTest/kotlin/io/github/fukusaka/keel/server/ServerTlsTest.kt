package io.github.fukusaka.keel.server

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.TlsCodecFactory
import io.github.fukusaka.keel.tls.TlsCodecResult
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Direct tests for the server-side TLS abstractions —
 * [ServerTls] / [ServerTlsStrategy] data classes and the
 * [TlsCodecServerInstaller] adapter — independent of [ServerConnector].
 */
class ServerTlsTest {

    /** Counts [createServerCodec] / [createClientCodec] / [close] for assertions. */
    private class RecordingTlsCodecFactory : TlsCodecFactory {
        var serverCodecCalls: Int = 0
            private set
        var clientCodecCalls: Int = 0
            private set
        var closeCalls: Int = 0
            private set
        var lastConfig: TlsConfig? = null
            private set

        override fun createServerCodec(config: TlsConfig): TlsCodec {
            serverCodecCalls++
            lastConfig = config
            return StubCodec()
        }
        override fun createClientCodec(config: TlsConfig): TlsCodec {
            clientCodecCalls++
            return StubCodec()
        }
        override fun close() {
            closeCalls++
        }
    }

    /** Minimal [TlsCodec] stub — never asked to protect / unprotect by these tests. */
    private class StubCodec : TlsCodec {
        override val isHandshakeComplete: Boolean = false
        override val negotiatedProtocol: String? = null
        override val peerCertificates: List<ByteArray> = emptyList()
        override fun protect(
            plaintext: IoBuf,
            ciphertext: IoBuf,
        ): TlsCodecResult = error("not used")
        override fun unprotect(
            ciphertext: IoBuf,
            plaintext: IoBuf,
        ): TlsCodecResult = error("not used")
        override fun close() {}
    }

    private val noopLogger = NoopLoggerFactory.logger("server-tls-test")

    private fun fakeChannel(): AbstractPipelinedChannel =
        object : AbstractPipelinedChannel(TestIoTransport(), noopLogger) {}

    // --- ServerTls ---

    @Test
    fun `ServerTls stores config and strategy as supplied`() {
        val config = TlsConfig()
        val strategy = ServerTlsStrategy.EngineNative
        val tls = ServerTls(config, strategy)

        assertSame(config, tls.config)
        assertSame(strategy, tls.strategy)
    }

    // --- ServerTlsStrategy ---

    @Test
    fun `EngineNative is a singleton data object`() {
        val a: ServerTlsStrategy = ServerTlsStrategy.EngineNative
        val b: ServerTlsStrategy = ServerTlsStrategy.EngineNative
        assertSame(a, b)
    }

    @Test
    fun `KeelCodec exposes the wrapped TlsCodecFactory`() {
        val factory = RecordingTlsCodecFactory()
        val strategy = ServerTlsStrategy.KeelCodec(factory)
        assertSame(factory, strategy.factory)
    }

    @Test
    fun `Custom exposes the wrapped TlsServerInstaller`() {
        val installer = TlsServerInstaller { _, _ ->
            // Deliberately empty: the test only checks that this instance is the one exposed.
        }
        val strategy = ServerTlsStrategy.Custom(installer)
        assertSame(installer, strategy.installer)
    }

    // --- TlsCodecServerInstaller ---

    @Test
    fun `installer adds TlsHandler at pipeline HEAD under the name tls`() {
        val factory = RecordingTlsCodecFactory()
        val installer = TlsCodecServerInstaller(factory)
        val channel = fakeChannel()
        val config = TlsConfig()

        installer.install(channel, config)

        val handler = channel.pipeline.get("tls")
        assertNotNull(handler, "TlsHandler must be installed under name 'tls'")
        assertIs<TlsHandler>(handler)
        assertEquals(1, factory.serverCodecCalls)
        assertSame(config, factory.lastConfig)
    }

    @Test
    fun `installer two-arg overload delegates to three-arg with the default plaintext buffer size`() {
        val factory = RecordingTlsCodecFactory()
        val installer = TlsCodecServerInstaller(factory)
        val channel = fakeChannel()

        installer.install(channel, TlsConfig())

        assertEquals(1, factory.serverCodecCalls, "server codec is created on install")
    }

    @Test
    fun `installer three-arg overload accepts a valid power-of-two plaintext buffer size`() {
        val factory = RecordingTlsCodecFactory()
        val installer = TlsCodecServerInstaller(factory)
        val channel = fakeChannel()

        installer.install(channel, TlsConfig(), plaintextBufferSize = 16_384)

        assertNotNull(channel.pipeline.get("tls"))
        assertEquals(1, factory.serverCodecCalls)
    }

    @Test
    fun `installer creates a fresh codec per install call`() {
        val factory = RecordingTlsCodecFactory()
        val installer = TlsCodecServerInstaller(factory)
        val config = TlsConfig()

        installer.install(fakeChannel(), config)
        installer.install(fakeChannel(), config)
        installer.install(fakeChannel(), config)

        assertEquals(3, factory.serverCodecCalls, "one codec per accepted connection")
        assertEquals(0, factory.clientCodecCalls, "server installer must not touch client codec")
    }

    @Test
    fun `installer does not close the factory`() {
        val factory = RecordingTlsCodecFactory()
        val installer = TlsCodecServerInstaller(factory)

        installer.install(fakeChannel(), TlsConfig())

        assertEquals(0, factory.closeCalls, "installer must not take ownership of the factory")
    }

    @Test
    fun `installer rejects a plaintext buffer size below the minimum via TlsHandler`() {
        val factory = RecordingTlsCodecFactory()
        val installer = TlsCodecServerInstaller(factory)

        assertFailsWith<IllegalArgumentException> {
            installer.install(fakeChannel(), TlsConfig(), plaintextBufferSize = 8_192)
        }
    }
}
