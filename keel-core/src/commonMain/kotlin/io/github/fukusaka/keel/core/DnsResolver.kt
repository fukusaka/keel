package io.github.fukusaka.keel.core

import kotlin.time.Duration

/**
 * Asynchronous DNS resolver interface.
 *
 * An engine calls [resolve] when it encounters a [Host.Name] in an
 * [InetSocketAddress]. IP literals bypass the resolver entirely.
 *
 * The interface is pluggable so callers can substitute caching or
 * async implementations (e.g. [CachingDnsResolver]). The default
 * resolver ([SYSTEM]) wraps the platform's blocking `getaddrinfo`
 * equivalent.
 *
 * Implementations must be safe to call from any coroutine context.
 */
interface DnsResolver {

    /**
     * Resolves [hostname] to one or more [IpAddress]es.
     *
     * @param hostname Raw hostname, e.g. `"example.com"`. IP literals
     *   are allowed but should normally be short-circuited by the
     *   caller.
     * @param hints Filter / timeout / canonical-name hints.
     * @return A non-empty list of addresses. Implementations throw if
     *   nothing could be resolved under the given hints.
     */
    suspend fun resolve(
        hostname: String,
        hints: ResolveHints = ResolveHints.DEFAULT,
    ): ResolverResult

    companion object {
        /** Built-in resolver delegating to the platform's system resolver. */
        val SYSTEM: DnsResolver get() = SystemDnsResolver
    }
}

/**
 * Result of a single [DnsResolver.resolve] call.
 *
 * @property addresses One or more resolved IPs in the order the
 *   resolver would like them tried (typically the OS's preferred order,
 *   e.g. IPv6-first when the host has a routable IPv6 stack).
 * @property canonicalName The canonical hostname, if the resolver was
 *   asked for it via [ResolveHints.canonicalName] and the platform
 *   provides one. Non-null only when explicitly requested.
 */
data class ResolverResult(
    val addresses: List<IpAddress>,
    val canonicalName: String? = null,
) {
    init { require(addresses.isNotEmpty()) { "addresses must not be empty" } }
}

/**
 * Tuning parameters for [DnsResolver.resolve].
 *
 * @property family IP family preference (Any / V4Only / V6Only).
 * @property canonicalName When true, resolvers that can fetch a
 *   canonical hostname (e.g. `getaddrinfo` with `AI_CANONNAME`) should
 *   populate [ResolverResult.canonicalName]. Off by default because it
 *   adds a round-trip on some platforms.
 * @property timeout Upper bound on the resolve call. When null, the
 *   caller is expected to apply its own `withTimeout` if needed.
 *   Resolvers honouring this value wrap their blocking call with a
 *   timeout themselves.
 */
data class ResolveHints(
    val family: FamilyPreference = FamilyPreference.Any,
    val canonicalName: Boolean = false,
    val timeout: Duration? = null,
) {
    companion object { val DEFAULT = ResolveHints() }
}

/**
 * IP family filter applied after resolution.
 *
 * `V4Only` / `V6Only` drop addresses of the other family. `Any` returns
 * whatever the underlying resolver reports.
 */
enum class FamilyPreference { Any, V4Only, V6Only }

/**
 * Built-in resolver that delegates to the platform's blocking system
 * resolver (`InetAddress.getAllByName` on JVM, `dns.lookup` on Node.js,
 * `getaddrinfo` on Native — see actual implementations).
 *
 * Returned from [DnsResolver.SYSTEM] and used as the default on
 * [IoEngineConfig.resolver].
 */
expect object SystemDnsResolver : DnsResolver

/**
 * Resolves the host component of this address (if it is a hostname) and
 * returns a list of concrete [IpAddress]es to connect to. For a
 * [Host.Ip] the singleton list is returned without invoking [resolver].
 *
 * The port is not part of the return value; callers pair it manually
 * when building per-candidate `InetSocketAddress`es.
 */
suspend fun InetSocketAddress.resolveAll(
    resolver: DnsResolver = DnsResolver.SYSTEM,
    hints: ResolveHints = ResolveHints.DEFAULT,
): List<IpAddress> = when (val h = host) {
    is Host.Ip -> listOf(h.address)
    is Host.Name -> resolver.resolve(h.value, hints).addresses.filterByFamily(hints.family)
}

/**
 * Resolves this address and returns the first usable [IpAddress].
 *
 * Phase 11 A-2 semantics: bind uses first, connect uses first
 * (sequential fallback over multiple candidates is deferred to a
 * follow-up PR).
 */
/**
 * Non-suspending variant for synchronous callers (e.g. pipeline-mode
 * bind). Returns the IP literal string if [host] is already a
 * [Host.Ip]; throws [UnsupportedOperationException] for hostnames
 * since DNS resolution is a suspend operation.
 */
fun InetSocketAddress.requireIpLiteral(): String = when (val h = host) {
    is Host.Ip -> h.address.toCanonicalString()
    is Host.Name -> throw UnsupportedOperationException(
        "synchronous call site cannot resolve hostname '${h.value}'; " +
            "pass an IP literal or use a suspending API",
    )
}

suspend fun InetSocketAddress.resolveFirst(
    resolver: DnsResolver = DnsResolver.SYSTEM,
    hints: ResolveHints = ResolveHints.DEFAULT,
): IpAddress = resolveAll(resolver, hints).firstOrNull()
    ?: error("no address resolved for $this (family=${hints.family})")

internal fun List<IpAddress>.filterByFamily(family: FamilyPreference): List<IpAddress> = when (family) {
    FamilyPreference.Any -> this
    FamilyPreference.V4Only -> filterIsInstance<IpAddress.V4>()
    FamilyPreference.V6Only -> filterIsInstance<IpAddress.V6>()
}
