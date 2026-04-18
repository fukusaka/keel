package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.core.DnsResolver
import io.github.fukusaka.keel.core.FamilyPreference
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.ResolveHints
import io.github.fukusaka.keel.core.resolveFirst

/**
 * DNS hints for resolving through the keel-native-posix socket path.
 *
 * [PosixSocketUtils] drives the socket layer with `inet_pton(AF_INET, ...)`
 * and `struct sockaddr_in`, so every address that reaches it must be an
 * IPv4 literal. Forcing [FamilyPreference.V4Only] at resolve time
 * prevents an IPv6 first-hop (e.g. `localhost` → `::1` from /etc/hosts)
 * from producing a string that `inet_pton` cannot parse. Remove once
 * the Native socket path gains IPv6 support.
 */
val POSIX_IPV4_RESOLVE_HINTS: ResolveHints = ResolveHints(family = FamilyPreference.V4Only)

/**
 * Resolves an [InetSocketAddress] to an IPv4 dotted-decimal literal
 * suitable for the Native POSIX socket path.
 *
 * IP-literal hosts short-circuit via [InetSocketAddress.resolveFirst];
 * hostnames go through [resolver] under [POSIX_IPV4_RESOLVE_HINTS].
 */
suspend fun InetSocketAddress.resolveForPosixSocket(resolver: DnsResolver): String =
    resolveFirst(resolver, POSIX_IPV4_RESOLVE_HINTS).toCanonicalString()
