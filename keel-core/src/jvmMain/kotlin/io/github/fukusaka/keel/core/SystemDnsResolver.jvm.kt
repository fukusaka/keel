package io.github.fukusaka.keel.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * JVM implementation of [DnsResolver] that delegates to
 * [InetAddress.getAllByName], run on [Dispatchers.IO] because the
 * underlying call is blocking.
 *
 * Canonical name resolution uses [InetAddress.getCanonicalHostName],
 * which triggers a second lookup (reverse DNS). Only requested when
 * [ResolveHints.canonicalName] is true.
 */
actual object SystemDnsResolver : DnsResolver {
    override suspend fun resolve(hostname: String, hints: ResolveHints): ResolverResult {
        val lookup: suspend () -> ResolverResult = { doLookup(hostname, hints) }
        val timeout = hints.timeout
        return if (timeout != null) withTimeout(timeout) { lookup() } else lookup()
    }

    // InjectDispatcher suppressed: this is the platform's built-in resolver — the blocking
    // InetAddress.getAllByName must run on Dispatchers.IO and there is no dispatcher
    // injection seam. Tests and custom resolvers substitute at the DnsResolver interface
    // level (via IoEngineConfig.resolver) rather than swapping the dispatcher here.
    @Suppress("InjectDispatcher")
    private suspend fun doLookup(hostname: String, hints: ResolveHints): ResolverResult = withContext(Dispatchers.IO) {
        val all = InetAddress.getAllByName(hostname)
        val addresses = all.mapNotNull { it.toIpAddress() }.filterByFamily(hints.family)
        require(addresses.isNotEmpty()) { "no addresses for $hostname under ${hints.family}" }
        val canonical = if (hints.canonicalName) all.firstOrNull()?.canonicalHostName else null
        ResolverResult(addresses, canonical)
    }
}

private fun InetAddress.toIpAddress(): IpAddress? = when (this) {
    is Inet4Address -> IpAddress.ofBytes(address)
    is Inet6Address -> IpAddress.ofBytes(address, scopeId)
    else -> null
}
