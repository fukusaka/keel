package io.github.fukusaka.keel.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * In-process caching wrapper around a delegate [DnsResolver].
 *
 * Every successful [delegate] resolution is stored for [ttl], capped by
 * LRU eviction at [maxSize] entries. Subsequent lookups for the same
 * hostname bypass the delegate entirely until the entry expires or is
 * evicted. Family hints still apply — the cache stores the resolver's
 * unfiltered [ResolverResult] and [resolve] re-filters per-call through
 * [FamilyPreference].
 *
 * **Opt-in.** The default on [IoEngineConfig.resolver] is
 * [DnsResolver.SYSTEM], which invokes `getaddrinfo` on every call and
 * relies on OS-level caching (NSS / nscd / systemd-resolved). Wrap with
 * `CachingDnsResolver` only when the hot path resolves the same
 * hostnames frequently — e.g. an RPC client pointed at a short list of
 * backend service names. Cold-path resolves or deployments where OS-level
 * caching is already sufficient should stay on the default.
 *
 * **Positive caching only.** Failed lookups are not cached; a transient
 * DNS outage does not lock future retries out of the resolver. Negative
 * caching is deferred until a concrete client use case justifies the
 * operational cost (DNS recovery lag, locked-in misconfiguration) of
 * carrying a second TTL.
 *
 * **Thread safety.** Concurrent calls are serialised through an internal
 * [Mutex]. No single-flight of concurrent cache misses is implemented —
 * two simultaneous misses for the same hostname each invoke [delegate].
 *
 * @property delegate Underlying resolver invoked on cache miss.
 * @property ttl How long a successful resolution stays cached.
 * @property maxSize LRU capacity. When exceeded, the least recently
 *   used entry is evicted.
 * @property timeSource Monotonic clock used for TTL checks. Override in
 *   tests with a deterministic [TimeSource] so expiry is controllable
 *   without sleeping.
 */
class CachingDnsResolver(
    private val delegate: DnsResolver,
    private val ttl: Duration = 30.seconds,
    private val maxSize: Int = 1024,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : DnsResolver {

    init {
        require(ttl > Duration.ZERO) { "ttl must be positive: $ttl" }
        require(maxSize > 0) { "maxSize must be positive: $maxSize" }
    }

    // LinkedHashMap with accessOrder-style LRU via explicit reordering.
    // MPP has no thread-safe LRU primitive; a plain mutable map + mutex
    // is the simplest portable option.
    private val entries = LinkedHashMap<String, Entry>(maxSize)
    private val mutex = Mutex()

    override suspend fun resolve(hostname: String, hints: ResolveHints): ResolverResult {
        val cached = mutex.withLock { lookup(hostname) }
        val base = cached ?: refresh(hostname, hints)
        // Family filter is applied at retrieval; the cache stores the
        // resolver's original family-neutral list so that different
        // hints over the same cached entry all work off one upstream
        // lookup.
        val filtered = base.addresses.filterByFamily(hints.family)
        require(filtered.isNotEmpty()) {
            "no addresses for '$hostname' under ${hints.family}"
        }
        return ResolverResult(filtered, base.canonicalName)
    }

    private suspend fun refresh(hostname: String, hints: ResolveHints): ResolverResult {
        // Use Any family for the upstream fetch so the cache entry
        // satisfies future V4Only / V6Only / Any queries from one
        // lookup. Forward the canonicalName / timeout flags as the
        // caller requested.
        val upstreamHints = hints.copy(family = FamilyPreference.Any)
        val fresh = delegate.resolve(hostname, upstreamHints)
        mutex.withLock { put(hostname, fresh) }
        return fresh
    }

    private fun lookup(hostname: String): ResolverResult? {
        val entry = entries[hostname] ?: return null
        if (entry.isExpired()) {
            entries.remove(hostname)
            return null
        }
        // Re-insert to move to most-recently-used position.
        entries.remove(hostname)
        entries[hostname] = entry
        return entry.result
    }

    private fun put(hostname: String, result: ResolverResult) {
        val entry = Entry(result, timeSource.markNow() + ttl)
        entries.remove(hostname)
        entries[hostname] = entry
        while (entries.size > maxSize) {
            val lru = entries.entries.iterator().next()
            entries.remove(lru.key)
        }
    }

    /** Removes [hostname] from the cache if present. */
    suspend fun invalidate(hostname: String) {
        mutex.withLock { entries.remove(hostname) }
    }

    /** Removes every cached entry. */
    suspend fun invalidateAll() {
        mutex.withLock { entries.clear() }
    }

    private class Entry(val result: ResolverResult, val expiresAt: TimeMark) {
        fun isExpired(): Boolean = expiresAt.hasPassedNow()
    }
}
