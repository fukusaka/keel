package io.github.fukusaka.keel.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
 * **Thread safety + single-flight.** Concurrent calls are serialised
 * through an internal [Mutex] for cache / in-flight bookkeeping, then
 * every concurrent miss for the same hostname awaits a single
 * [Deferred] produced by [scope]. The upstream fetch runs inside
 * [scope] — a [SupervisorJob]-backed scope owned by this resolver —
 * so cancellation of any individual caller does not cancel the shared
 * work and does not propagate cancellation to unrelated joiners. If
 * every caller cancels before the fetch completes the async keeps
 * running (its result will still populate the cache); call [close] to
 * tear it down at shutdown.
 *
 * When the upstream fetch fails the in-flight entry is dropped so a
 * subsequent caller starts a fresh attempt (positive-only policy) and
 * the same failure is broadcast to any joiners still awaiting.
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

    // Internal scope for the single-flight upstream fetches. The
    // SupervisorJob lets one fetch failure / cancellation stay local
    // without tearing down the resolver, and Dispatchers.Unconfined
    // avoids dispatching the coroutine body — delegate.resolve() will
    // switch to its own dispatcher (e.g. Dispatchers.IO on JVM) for
    // the blocking getaddrinfo call. Caller lifecycles never enter
    // this scope.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    // LinkedHashMap with accessOrder-style LRU via explicit reordering.
    // MPP has no thread-safe LRU primitive; a plain mutable map + mutex
    // is the simplest portable option.
    private val entries = LinkedHashMap<String, Entry>(maxSize)

    // Single-flight registry: one shared deferred per in-flight upstream
    // lookup, reused by every concurrent miss for that hostname.
    private val inFlight = mutableMapOf<String, Deferred<ResolverResult>>()

    private val mutex = Mutex()

    override suspend fun resolve(hostname: String, hints: ResolveHints): ResolverResult {
        val base = loadBase(hostname, hints)
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

    private suspend fun loadBase(hostname: String, hints: ResolveHints): ResolverResult {
        // Decide cached-hit vs await under the lock. If nothing is in
        // flight we start a new async inside `scope` so cancellation of
        // the current caller will not cancel the fetch (or other
        // joiners). The async body itself reacquires the mutex to
        // populate the cache and clear the in-flight slot.
        val action = mutex.withLock {
            lookup(hostname)?.let { return@withLock Action.Cached(it) }
            val existing = inFlight[hostname]
            if (existing != null) {
                Action.Await(existing)
            } else {
                val started = scope.async { runFetch(hostname, hints) }
                inFlight[hostname] = started
                Action.Await(started)
            }
        }
        return when (action) {
            is Action.Cached -> action.result
            is Action.Await -> action.deferred.await()
        }
    }

    private suspend fun runFetch(hostname: String, hints: ResolveHints): ResolverResult {
        // Use Any family for the upstream fetch so the cache entry
        // satisfies future V4Only / V6Only / Any queries from one
        // lookup. Forward the canonicalName / timeout flags as the
        // caller requested.
        val upstreamHints = hints.copy(family = FamilyPreference.Any)
        try {
            val fresh = delegate.resolve(hostname, upstreamHints)
            mutex.withLock {
                put(hostname, fresh)
                inFlight.remove(hostname)
            }
            return fresh
        } catch (e: Throwable) {
            // Drop the in-flight entry so the next caller starts a
            // fresh attempt (positive-only policy: failures are not
            // cached). The deferred's completeExceptionally is driven
            // by rethrowing here — awaiting joiners then see the same
            // failure through their own await().
            mutex.withLock { inFlight.remove(hostname) }
            throw e
        }
    }

    private sealed class Action {
        class Cached(val result: ResolverResult) : Action()
        class Await(val deferred: Deferred<ResolverResult>) : Action()
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

    /**
     * Cancels the internal scope, tearing down any still-running
     * upstream fetches. Safe to call more than once; subsequent
     * [resolve] calls will fail because the async cannot be started
     * on a cancelled scope. Intended for process shutdown.
     */
    fun close() {
        scope.cancel()
    }

    private class Entry(val result: ResolverResult, val expiresAt: TimeMark) {
        fun isExpired(): Boolean = expiresAt.hasPassedNow()
    }
}
