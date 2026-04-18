package io.github.fukusaka.keel.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DnsResolverTest {

    private val loopbackV4 = IpAddress.V4.LOOPBACK
    private val loopbackV6 = IpAddress.V6.LOOPBACK

    @Test
    fun `resolveAll returns the literal without invoking resolver for Host Ip`() = runTest {
        val throwingResolver = object : DnsResolver {
            override suspend fun resolve(hostname: String, hints: ResolveHints): ResolverResult =
                error("resolver must not be invoked for Host.Ip")
        }
        val addr = InetSocketAddress("127.0.0.1", 8080)
        assertEquals(listOf(loopbackV4), addr.resolveAll(throwingResolver))
    }

    @Test
    fun `resolveAll delegates to resolver for Host Name`() = runTest {
        val resolver = StubResolver(listOf(loopbackV4, loopbackV6))
        val addr = InetSocketAddress("example.com", 443)
        assertEquals(listOf(loopbackV4, loopbackV6), addr.resolveAll(resolver))
        assertEquals("example.com", resolver.lastHostname)
    }

    @Test
    fun `FamilyPreference V4Only filters V6 out`() = runTest {
        val resolver = StubResolver(listOf(loopbackV6, loopbackV4))
        val addr = InetSocketAddress("example.com", 443)
        val hints = ResolveHints(family = FamilyPreference.V4Only)
        assertEquals(listOf(loopbackV4), addr.resolveAll(resolver, hints))
    }

    @Test
    fun `FamilyPreference V6Only filters V4 out`() = runTest {
        val resolver = StubResolver(listOf(loopbackV4, loopbackV6))
        val addr = InetSocketAddress("example.com", 443)
        val hints = ResolveHints(family = FamilyPreference.V6Only)
        assertEquals(listOf(loopbackV6), addr.resolveAll(resolver, hints))
    }

    @Test
    fun `resolveFirst returns first candidate`() = runTest {
        val resolver = StubResolver(listOf(loopbackV6, loopbackV4))
        val addr = InetSocketAddress("example.com", 443)
        assertEquals(loopbackV6, addr.resolveFirst(resolver))
    }

    @Test
    fun `resolveFirst throws when family filter empties the list`() = runTest {
        val resolver = StubResolver(listOf(loopbackV6))
        val addr = InetSocketAddress("example.com", 443)
        val hints = ResolveHints(family = FamilyPreference.V4Only)
        assertFailsWith<IllegalStateException> {
            addr.resolveFirst(resolver, hints)
        }
    }

    @Test
    fun `requireIpLiteral returns canonical form for Host Ip`() {
        assertEquals("127.0.0.1", InetSocketAddress("127.0.0.1", 80).requireIpLiteral())
        assertEquals("::1", InetSocketAddress("::1", 80).requireIpLiteral())
    }

    @Test
    fun `requireIpLiteral throws for Host Name`() {
        assertFailsWith<UnsupportedOperationException> {
            InetSocketAddress("example.com", 80).requireIpLiteral()
        }
    }

    @Test
    fun `ResolverResult rejects empty addresses`() {
        assertFailsWith<IllegalArgumentException> {
            ResolverResult(emptyList())
        }
    }

    @Test
    fun `ResolveHints DEFAULT uses Any family and no canonical name`() {
        val d = ResolveHints.DEFAULT
        assertEquals(FamilyPreference.Any, d.family)
        assertTrue(!d.canonicalName)
        assertNull(d.timeout)
    }

    @Test
    fun `IoEngineConfig default resolver is SystemDnsResolver`() {
        val cfg = IoEngineConfig()
        assertEquals(DnsResolver.SYSTEM, cfg.resolver)
    }

    private class StubResolver(private val addresses: List<IpAddress>) : DnsResolver {
        var lastHostname: String? = null
        override suspend fun resolve(hostname: String, hints: ResolveHints): ResolverResult {
            lastHostname = hostname
            return ResolverResult(addresses)
        }
    }
}
