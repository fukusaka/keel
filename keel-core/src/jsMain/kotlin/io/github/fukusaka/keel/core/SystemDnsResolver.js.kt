package io.github.fukusaka.keel.core

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Node.js implementation of [DnsResolver] wrapping `dns.lookup(host, { all: true })`.
 *
 * `dns.lookup` is non-blocking but runs on libuv's thread pool, so no
 * dispatcher switch is required from the coroutine side.
 *
 * Node's `dns.lookup` does not expose canonical names (that needs
 * `dns.resolve4` / `dns.resolve6` which skip /etc/hosts); so
 * [ResolveHints.canonicalName] is ignored on this backend.
 */
actual object SystemDnsResolver : DnsResolver {
    override suspend fun resolve(hostname: String, hints: ResolveHints): ResolverResult {
        val timeout = hints.timeout
        return if (timeout != null) withTimeout(timeout) { doLookup(hostname, hints) } else doLookup(hostname, hints)
    }

    private suspend fun doLookup(hostname: String, hints: ResolveHints): ResolverResult =
        suspendCancellableCoroutine { cont ->
            val options = js("{}")
            options.all = true
            when (hints.family) {
                FamilyPreference.V4Only -> options.family = 4
                FamilyPreference.V6Only -> options.family = 6
                FamilyPreference.Any -> { /* no constraint */ }
            }
            Dns.lookup(hostname, options) { err: dynamic, addresses: dynamic ->
                if (err != null) {
                    cont.resumeWithException(RuntimeException("dns.lookup failed for $hostname: ${err.message}"))
                } else {
                    val list = mutableListOf<IpAddress>()
                    val len: Int = addresses.length as Int
                    for (i in 0 until len) {
                        val entry = addresses[i]
                        val addr: String = entry.address as String
                        val parsed = IpAddress.parseOrNull(addr)
                        if (parsed != null) list.add(parsed)
                    }
                    if (list.isEmpty()) {
                        cont.resumeWithException(RuntimeException("no addresses resolved for $hostname"))
                    } else {
                        cont.resume(ResolverResult(list))
                    }
                }
            }
        }
}

@JsModule("dns")
@JsNonModule
private external object Dns {
    fun lookup(hostname: String, options: dynamic, callback: (err: dynamic, addresses: dynamic) -> Unit)
}
