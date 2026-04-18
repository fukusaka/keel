package io.github.fukusaka.keel.core

/**
 * Native implementation of [DnsResolver].
 *
 * Current Phase 11 A-2 scope: IP literals only. Hostnames throw
 * [UnsupportedOperationException]. Phase 11 PR B will replace this
 * stub with a `getaddrinfo`-based resolver run on [kotlinx.coroutines.Dispatchers.IO].
 */
actual object SystemDnsResolver : DnsResolver {
    override suspend fun resolve(hostname: String, hints: ResolveHints): ResolverResult {
        val literal = IpAddress.parseOrNull(hostname)
            ?: throw UnsupportedOperationException(
                "Native SystemDnsResolver does not yet resolve hostnames (got '$hostname'); " +
                    "use an IP literal until Phase 11 PR B adds getaddrinfo support",
            )
        val filtered = listOf(literal).filterByFamily(hints.family)
        require(filtered.isNotEmpty()) { "address '$hostname' excluded by family filter ${hints.family}" }
        return ResolverResult(filtered)
    }
}
