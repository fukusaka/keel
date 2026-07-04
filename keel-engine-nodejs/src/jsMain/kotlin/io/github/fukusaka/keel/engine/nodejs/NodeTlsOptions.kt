package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsTrustSource
import io.github.fukusaka.keel.tls.TlsVerifyMode
import io.github.fukusaka.keel.tls.TlsVersion
import io.github.fukusaka.keel.tls.asPem

/**
 * Builds the `tls.createServer(...)` options object honouring every
 * server-relevant axis of [TlsConfig] on the Node.js backend.
 *
 * Handled axes:
 *  * [TlsConfig.certificates]   → `options.key` / `options.cert` (PEM strings)
 *  * [TlsConfig.trustAnchors]   → `options.ca`
 *    (a PEM string with one or more `-----BEGIN CERTIFICATE-----` blocks;
 *    Node accepts a concatenated PEM here)
 *  * [TlsConfig.verifyMode]     → `options.requestCert` / `options.rejectUnauthorized`
 *    (`NONE` → both `false`; `PEER` → request but do not reject on failure;
 *    `REQUIRED` → request and reject on failure — mTLS)
 *  * [TlsConfig.alpnProtocols]  → `options.ALPNProtocols`
 *  * [TlsConfig.minVersion] / [TlsConfig.maxVersion] → `options.minVersion` / `options.maxVersion`
 *    (JSSE-style strings `"TLSv1.2"` / `"TLSv1.3"` — Node accepts the same
 *    names its `tls.getMinVersion()` / `getMaxVersion()` return)
 *
 * Extracted from `NodeEngine.createServer` so the options object can be
 * built and inspected without standing up a listener (see
 * `NodeTlsOptionsTest`).
 */
internal object NodeTlsOptions {

    /**
     * Builds a plain JS object (`dynamic`) suitable for
     * `tls.createServer(options, ...)` with every server axis of [config]
     * honoured.
     *
     * Throws [IllegalArgumentException] via `requireNotNull` when
     * [TlsConfig.certificates] is missing — Node's listener-level TLS has
     * no anonymous mode.
     */
    fun build(config: TlsConfig): dynamic {
        val certs = requireNotNull(config.certificates) {
            "Node.js listener-level TLS requires certificates"
        }.asPem()

        val options = js("{}")
        options.key = certs.privateKeyPem
        options.cert = certs.certificatePem

        applyTrustAnchors(options, config.trustAnchors)
        applyVerifyMode(options, config.verifyMode)
        applyAlpn(options, config.alpnProtocols)
        applyVersionRange(options, config)

        return options
    }

    private fun applyTrustAnchors(options: dynamic, trust: TlsTrustSource?) {
        when (trust) {
            null,
            TlsTrustSource.SystemDefault,
            -> {
                // Nothing to configure — Node falls back to the built-in
                // set of well-known CAs (Mozilla). This matches keel's
                // other backends' SystemDefault semantics.
            }
            is TlsTrustSource.Pem -> {
                options.ca = trust.caPem
            }
            TlsTrustSource.InsecureTrustAll -> {
                // Node does not have an "accept any" verify hook the way
                // keel's TlsCodec backends do. Skipping trustAnchors here
                // is equivalent to SystemDefault; production callers who
                // really want to accept anything on the server side
                // should combine `verifyMode = NONE` with this trust
                // source instead, which turns off client-cert
                // verification entirely.
            }
        }
    }

    private fun applyVerifyMode(options: dynamic, mode: TlsVerifyMode) {
        when (mode) {
            TlsVerifyMode.NONE -> {
                options.requestCert = false
                options.rejectUnauthorized = false
            }
            TlsVerifyMode.PEER -> {
                // Ask for a client cert but do not reject the handshake
                // if the peer supplies none — matches keel's PEER
                // ("want auth but continue") semantics precisely, which
                // the NW backend cannot express because Apple's
                // sec_protocol_options_set_peer_authentication_optional
                // is API_UNAVAILABLE.
                options.requestCert = true
                options.rejectUnauthorized = false
            }
            TlsVerifyMode.REQUIRED -> {
                options.requestCert = true
                options.rejectUnauthorized = true
            }
        }
    }

    private fun applyAlpn(options: dynamic, protocols: List<String>?) {
        if (protocols.isNullOrEmpty()) return
        options.ALPNProtocols = protocols.toTypedArray()
    }

    private fun applyVersionRange(options: dynamic, config: TlsConfig) {
        options.minVersion = tlsVersionName(config.minVersion)
        val max = config.maxVersion ?: TlsVersion.TLS1_3
        options.maxVersion = tlsVersionName(max)
    }

    private fun tlsVersionName(version: TlsVersion): String = when (version) {
        TlsVersion.TLS1_2 -> "TLSv1.2"
        TlsVersion.TLS1_3 -> "TLSv1.3"
    }
}
