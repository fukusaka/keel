package io.github.fukusaka.keel.core

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.AF_UNSPEC
import platform.posix.AI_CANONNAME
import platform.posix.SOCK_STREAM
import platform.posix.addrinfo
import platform.posix.freeaddrinfo
import platform.posix.gai_strerror
import platform.posix.getaddrinfo
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6

/**
 * Native implementation of [DnsResolver] backed by POSIX
 * `getaddrinfo(3)`.
 *
 * `getaddrinfo` is blocking (it issues /etc/hosts lookups, UDP queries
 * to the configured resolver, and /etc/nsswitch.conf-driven NSS plugin
 * calls), so the implementation wraps the call in
 * `withContext(Dispatchers.Default)`. `Dispatchers.IO` is `internal`
 * on Native in kotlinx-coroutines 1.10, so the well-sized Default pool
 * is used instead; for production workloads wrap this resolver with
 * [CachingDnsResolver] (Phase 11 PR D) so the blocking path is rarely
 * hit. IP literals short-circuit the blocking path entirely:
 * [IpAddress.parseOrNull] is tried first and, on success, the result
 * is returned without dispatching.
 *
 * `ai_addr` is accessed via `reinterpret<sockaddr_in>` /
 * `reinterpret<sockaddr_in6>` and the raw bytes of `sin_addr` /
 * `sin6_addr` are copied via [readBytes]. This avoids the
 * platform-specific `in6_addr` union field name (`__in6_u.__u6_addr8`
 * on Linux vs. `__u6_addr.__u6_addr8` on macOS), which does not
 * commonize cleanly in Kotlin/Native. IPv6 scope IDs propagate
 * through `sin6_scope_id` into [IpAddress.V6.scopeId].
 */
@OptIn(ExperimentalForeignApi::class)
actual object SystemDnsResolver : DnsResolver {
    override suspend fun resolve(hostname: String, hints: ResolveHints): ResolverResult {
        // Fast path: numeric IP literals never need a syscall.
        val literal = IpAddress.parseOrNull(hostname)
        if (literal != null) {
            val filtered = listOf(literal).filterByFamily(hints.family)
            require(filtered.isNotEmpty()) {
                "address '$hostname' excluded by family filter ${hints.family}"
            }
            return ResolverResult(filtered)
        }

        val timeout = hints.timeout
        return if (timeout != null) withTimeout(timeout) { doLookup(hostname, hints) } else doLookup(hostname, hints)
    }

    // InjectDispatcher suppressed: this is the platform's built-in resolver — the blocking
    // getaddrinfo(3) call must run off the caller's dispatcher, but there is no injection
    // seam. Tests and custom resolvers substitute at the DnsResolver interface level
    // (via IoEngineConfig.resolver) rather than swapping the dispatcher here.
    @Suppress("InjectDispatcher")
    private suspend fun doLookup(hostname: String, hints: ResolveHints): ResolverResult =
        withContext(Dispatchers.Default) {
            memScoped {
                val hintsStruct = alloc<addrinfo>().apply {
                    ai_family = when (hints.family) {
                        FamilyPreference.Any -> AF_UNSPEC
                        FamilyPreference.V4Only -> AF_INET
                        FamilyPreference.V6Only -> AF_INET6
                    }
                    ai_socktype = SOCK_STREAM
                    ai_flags = if (hints.canonicalName) AI_CANONNAME else 0
                }

                val resultPtr = allocPointerTo<addrinfo>()
                val rc = getaddrinfo(hostname, null, hintsStruct.ptr, resultPtr.ptr)
                if (rc != 0) {
                    val msg = gai_strerror(rc)?.toKString() ?: "error $rc"
                    throw RuntimeException("getaddrinfo failed for '$hostname': $msg")
                }

                try {
                    val addresses = mutableListOf<IpAddress>()
                    var canonical: String? = null
                    var current: CPointer<addrinfo>? = resultPtr.value
                    while (current != null) {
                        val info = current.pointed
                        if (canonical == null && info.ai_canonname != null) {
                            canonical = info.ai_canonname!!.toKString()
                        }
                        info.extractAddress()?.let(addresses::add)
                        current = info.ai_next
                    }
                    val filtered = addresses.filterByFamily(hints.family)
                    require(filtered.isNotEmpty()) {
                        "no addresses for '$hostname' under ${hints.family}"
                    }
                    ResolverResult(filtered, canonical)
                } finally {
                    freeaddrinfo(resultPtr.value)
                }
            }
        }

    private fun addrinfo.extractAddress(): IpAddress? {
        val sa = ai_addr ?: return null
        return when (ai_family) {
            AF_INET -> {
                val sin = sa.reinterpret<sockaddr_in>().pointed
                IpAddress.ofBytes(sin.sin_addr.ptr.reinterpret<UByteVar>().readBytes(IPV4_BYTE_LEN))
            }
            AF_INET6 -> {
                val sin6 = sa.reinterpret<sockaddr_in6>().pointed
                IpAddress.ofBytes(
                    sin6.sin6_addr.ptr.reinterpret<UByteVar>().readBytes(IPV6_BYTE_LEN),
                    sin6.sin6_scope_id.toInt(),
                )
            }
            else -> null
        }
    }

    private const val IPV4_BYTE_LEN = 4
    private const val IPV6_BYTE_LEN = 16
}
