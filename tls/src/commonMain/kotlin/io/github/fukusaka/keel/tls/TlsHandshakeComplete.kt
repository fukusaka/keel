package io.github.fukusaka.keel.tls

/**
 * User event fired through the pipeline when the TLS handshake completes.
 *
 * Downstream handlers can listen for this event via
 * [ChannelInboundHandler.onUserEvent][io.github.fukusaka.keel.pipeline.ChannelInboundHandler.onUserEvent]
 * to take action after TLS is established (e.g., send the first application message).
 *
 * @param negotiatedProtocol The ALPN protocol selected during handshake, or null.
 * @param cipherSuite The negotiated cipher suite name, or null if unavailable.
 */
data class TlsHandshakeComplete(
    val negotiatedProtocol: String?,
    val cipherSuite: String?,
)
