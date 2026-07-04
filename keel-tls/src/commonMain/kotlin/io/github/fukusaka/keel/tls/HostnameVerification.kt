package io.github.fukusaka.keel.tls

/**
 * Resolves whether a codec should verify the peer certificate's hostname, and
 * against which name, from [TlsConfig.verifyHostname] / [TlsConfig.verifyMode]
 * / [TlsConfig.serverName].
 *
 * Returns the hostname to verify against ([TlsConfig.serverName]) when hostname
 * verification should be active, or `null` when it should be skipped. Backends
 * enable their hostname-check mechanism iff the result is non-null.
 *
 * Hostname verification applies to client codecs only — a server does not
 * verify a peer hostname — so [isClient] gates it. The tri-state contract:
 *
 * - `verifyHostname == false`: never (chain-only, per [TlsConfig.verifyHostname]).
 * - `verifyHostname == true`: always ([TlsConfig.serverName] guaranteed non-null
 *   by [TlsConfig]'s init check).
 * - `verifyHostname == null`: secure-by-default — verify when [verifyMode] is
 *   not [TlsVerifyMode.NONE] and [serverName] is set.
 */
public fun TlsConfig.hostnameToVerify(isClient: Boolean): String? {
    if (!isClient) return null
    return when (verifyHostname) {
        false -> null
        true -> serverName
        null -> if (verifyMode != TlsVerifyMode.NONE) serverName else null
    }
}
