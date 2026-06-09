package io.github.fukusaka.keel.server

import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.tls.TlsCodecFactory
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsHandler
import io.github.fukusaka.keel.tls.TlsHandler.Companion.TLS_PLAINTEXT_BUF_SIZE_DEFAULT

/**
 * [TlsServerInstaller] adapter that installs keel's `TlsHandler` at the
 * pipeline HEAD using a [TlsCodecFactory] from `:keel-tls`.
 *
 * The factory creates a server-side codec via [TlsCodecFactory.createServerCodec]
 * for every accepted connection; the installer wraps that codec in
 * `TlsHandler` and inserts it as the first handler in the pipeline so
 * TLS protection sits closest to the transport layer.
 *
 * Lives in `:keel-server` so `:keel-tls` does not need to know about
 * server-side install plumbing — the inheritance `TlsCodecFactory : TlsInstaller`
 * was previously the layering shortcut, replaced by this adapter so
 * `:keel-tls` only owns protocol primitives.
 *
 * @param factory the codec factory to delegate codec creation to. The
 *   adapter does not take ownership — the caller is responsible for
 *   `factory.close()` when the factory is no longer needed.
 */
public class TlsCodecServerInstaller(
    private val factory: TlsCodecFactory,
) : TlsServerInstaller {

    /**
     * Installs `TlsHandler` (wrapping a fresh server codec) at the pipeline
     * HEAD, using the default [TLS_PLAINTEXT_BUF_SIZE_DEFAULT] (16 KiB).
     */
    override fun install(channel: PipelinedChannel, config: TlsConfig) {
        install(channel, config, TLS_PLAINTEXT_BUF_SIZE_DEFAULT)
    }

    /**
     * Installs `TlsHandler` (wrapping a fresh server codec) at the pipeline
     * HEAD with the requested [plaintextBufferSize] — the buffer size the
     * downstream codec sees as its "recv segment" on this TLS connection.
     * Forwards the value into the `TlsHandler` constructor; validation is
     * performed there via
     * [TlsHandler.requireValidPlaintextBufferSize][io.github.fukusaka.keel.tls.TlsHandler.Companion.requireValidPlaintextBufferSize].
     */
    override fun install(channel: PipelinedChannel, config: TlsConfig, plaintextBufferSize: Int) {
        val codec = factory.createServerCodec(config)
        channel.pipeline.addFirst("tls", TlsHandler(codec, plaintextBufferSize, config.handshakeTimeoutMillis))
    }
}
