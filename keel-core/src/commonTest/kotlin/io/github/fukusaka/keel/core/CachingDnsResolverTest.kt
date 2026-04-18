package io.github.fukusaka.keel.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class CachingDnsResolverTest {

    @Test
    fun `cache hit skips the delegate`() = runTest {
        val delegate = CountingResolver(listOf(IpAddress.V4.LOOPBACK))
        val cache = CachingDnsResolver(delegate, ttl = 30.seconds)

        cache.resolve("example.com")
        cache.resolve("example.com")
        cache.resolve("example.com")

        assertEquals(1, delegate.calls, "subsequent lookups should hit the cache")
    }

    @Test
    fun `cache miss populates the entry`() = runTest {
        val delegate = CountingResolver(listOf(IpAddress.V4.LOOPBACK))
        val cache = CachingDnsResolver(delegate, ttl = 30.seconds)

        val result = cache.resolve("example.com")

        assertEquals(listOf(IpAddress.V4.LOOPBACK), result.addresses)
        assertEquals(1, delegate.calls)
    }

    @Test
    fun `TTL expiry forces a re-fetch`() = runTest {
        val ts = TestTimeSource()
        val delegate = CountingResolver(listOf(IpAddress.V4.LOOPBACK))
        val cache = CachingDnsResolver(delegate, ttl = 1.seconds, timeSource = ts)

        cache.resolve("example.com")
        ts += 500.milliseconds
        cache.resolve("example.com")
        assertEquals(1, delegate.calls, "still cached inside TTL")

        ts += 600.milliseconds
        cache.resolve("example.com")
        assertEquals(2, delegate.calls, "re-fetched after TTL expiry")
    }

    @Test
    fun `LRU evicts the oldest entry beyond maxSize`() = runTest {
        val delegate = CountingResolver(listOf(IpAddress.V4.LOOPBACK))
        val cache = CachingDnsResolver(delegate, ttl = 30.seconds, maxSize = 2)

        cache.resolve("a.example") // populate A
        cache.resolve("b.example") // populate B
        cache.resolve("a.example") // touch A (now most recent)
        cache.resolve("c.example") // populate C → should evict B (LRU), not A

        cache.resolve("a.example") // still cached
        assertEquals(3, delegate.calls, "A and C cached, B evicted; total misses = 3")

        cache.resolve("b.example") // B evicted → miss
        assertEquals(4, delegate.calls)
    }

    @Test
    fun `family filter reuses the same upstream entry`() = runTest {
        val delegate = CountingResolver(listOf(IpAddress.V4.LOOPBACK, IpAddress.V6.LOOPBACK))
        val cache = CachingDnsResolver(delegate, ttl = 30.seconds)

        val any = cache.resolve("example.com", ResolveHints(family = FamilyPreference.Any))
        val v4 = cache.resolve("example.com", ResolveHints(family = FamilyPreference.V4Only))
        val v6 = cache.resolve("example.com", ResolveHints(family = FamilyPreference.V6Only))

        assertEquals(2, any.addresses.size)
        assertEquals(listOf(IpAddress.V4.LOOPBACK), v4.addresses)
        assertEquals(listOf(IpAddress.V6.LOOPBACK), v6.addresses)
        assertEquals(1, delegate.calls, "one upstream fetch serves all three family filters")
    }

    @Test
    fun `invalidate forces a re-fetch on the next call`() = runTest {
        val delegate = CountingResolver(listOf(IpAddress.V4.LOOPBACK))
        val cache = CachingDnsResolver(delegate, ttl = 30.seconds)

        cache.resolve("example.com")
        cache.invalidate("example.com")
        cache.resolve("example.com")

        assertEquals(2, delegate.calls)
    }

    @Test
    fun `invalidateAll clears every entry`() = runTest {
        val delegate = CountingResolver(listOf(IpAddress.V4.LOOPBACK))
        val cache = CachingDnsResolver(delegate, ttl = 30.seconds)

        cache.resolve("a.example")
        cache.resolve("b.example")
        cache.invalidateAll()
        cache.resolve("a.example")
        cache.resolve("b.example")

        assertEquals(4, delegate.calls)
    }

    @Test
    fun `empty filter result fails with a clear error`() = runTest {
        val delegate = CountingResolver(listOf(IpAddress.V6.LOOPBACK))
        val cache = CachingDnsResolver(delegate, ttl = 30.seconds)

        assertFailsWith<IllegalArgumentException> {
            cache.resolve("example.com", ResolveHints(family = FamilyPreference.V4Only))
        }
    }

    @Test
    fun `delegate failures are not cached`() = runTest {
        val delegate = FlakyResolver()
        val cache = CachingDnsResolver(delegate, ttl = 30.seconds)

        assertFailsWith<RuntimeException> { cache.resolve("example.com") }
        // Second call also hits the delegate because failures are not
        // cached (positive-only caching policy).
        val ok = cache.resolve("example.com")
        assertEquals(listOf(IpAddress.V4.LOOPBACK), ok.addresses)
        assertEquals(2, delegate.calls)
    }

    @Test
    fun `constructor rejects non-positive TTL`() {
        val delegate = CountingResolver(listOf(IpAddress.V4.LOOPBACK))
        assertFailsWith<IllegalArgumentException> {
            CachingDnsResolver(delegate, ttl = kotlin.time.Duration.ZERO)
        }
    }

    @Test
    fun `constructor rejects non-positive maxSize`() {
        val delegate = CountingResolver(listOf(IpAddress.V4.LOOPBACK))
        assertFailsWith<IllegalArgumentException> {
            CachingDnsResolver(delegate, maxSize = 0)
        }
    }

    @Test
    fun `canonicalName is preserved across cache hits`() = runTest {
        val delegate = CountingResolver(
            addresses = listOf(IpAddress.V4.LOOPBACK),
            canonicalName = "example.com.",
        )
        val cache = CachingDnsResolver(delegate, ttl = 30.seconds)

        val first = cache.resolve("example.com", ResolveHints(canonicalName = true))
        val second = cache.resolve("example.com", ResolveHints(canonicalName = true))

        assertEquals("example.com.", first.canonicalName)
        assertEquals("example.com.", second.canonicalName)
        assertEquals(1, delegate.calls)
    }

    private class CountingResolver(
        private val addresses: List<IpAddress>,
        private val canonicalName: String? = null,
    ) : DnsResolver {
        var calls: Int = 0
            private set

        override suspend fun resolve(hostname: String, hints: ResolveHints): ResolverResult {
            calls++
            return ResolverResult(addresses, canonicalName)
        }
    }

    /** Fails once, then succeeds. */
    private class FlakyResolver : DnsResolver {
        var calls: Int = 0
            private set

        override suspend fun resolve(hostname: String, hints: ResolveHints): ResolverResult {
            calls++
            return if (calls == 1) {
                throw RuntimeException("simulated transient failure")
            } else {
                ResolverResult(listOf(IpAddress.V4.LOOPBACK))
            }
        }
    }

    // Guard: data class defaults should expose non-null canonicalName only when asked.
    @Test
    fun `default ResolveHints does not request canonicalName`() {
        assertNull(ResolveHints.DEFAULT.canonicalName.takeIf { it })
        assertTrue(!ResolveHints.DEFAULT.canonicalName)
    }
}
