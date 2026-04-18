package io.github.fukusaka.keel.core

/**
 * A resolved IP address.
 *
 * [V4] carries a 32-bit network-order value; [V6] carries two 64-bit halves
 * plus an optional IPv6 scope id (`fe80::1%eth0` → `scopeId` is the interface
 * index, see `if_nametoindex(3)`). Both variants are `data class`es, so
 * `equals` / `hashCode` / `copy` are field-wise — the in-memory form
 * (`Int` / `ULong`) is deliberate to avoid per-address heap allocation.
 *
 * Byte arrays are exposed on demand via [toByteArray] and are freshly
 * allocated each call; they are a view, not the storage.
 *
 * String parsing lives in the [Companion] and is implemented in pure
 * Kotlin so the same code runs on JVM, Native, and JS. The grammar
 * accepts:
 * - IPv4 dotted-decimal: `"1.2.3.4"` (each octet 0..255)
 * - IPv6 canonical: `"2001:db8::1"`, `"::"`, `"::1"`
 * - IPv6 with IPv4-embedded suffix: `"::ffff:1.2.3.4"`
 * - IPv6 wrapped in brackets: `"[::1]"`
 * - IPv6 with scope: `"fe80::1%eth0"` or `"fe80::1%2"`
 */
sealed class IpAddress {

    /** Freshly-allocated byte view (4 bytes for V4, 16 bytes for V6). */
    abstract fun toByteArray(): ByteArray

    /** RFC 5952 canonical form for V6, dotted-decimal for V4. `%scope` appended when present. */
    abstract fun toCanonicalString(): String

    override fun toString(): String = toCanonicalString()

    /**
     * IPv4 address stored as a single 32-bit `Int` in network byte order.
     * Byte 0 (most significant) is the first dotted octet.
     */
    data class V4(val value: Int) : IpAddress() {

        override fun toByteArray(): ByteArray = byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

        override fun toCanonicalString(): String {
            val b = toByteArray()
            return "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}." +
                "${b[2].toInt() and 0xFF}.${b[3].toInt() and 0xFF}"
        }

        companion object {
            val ANY = V4(0)
            val LOOPBACK = V4(0x7F000001)
        }
    }

    /**
     * IPv6 address stored as two 64-bit `ULong`s plus an optional scope id.
     * [high] holds bytes 0..7 (most significant), [low] holds bytes 8..15.
     */
    data class V6(
        val high: ULong,
        val low: ULong,
        val scopeId: Int = 0,
    ) : IpAddress() {

        override fun toByteArray(): ByteArray {
            val b = ByteArray(16)
            for (i in 0 until 8) b[i] = ((high shr (56 - i * 8)) and 0xFFu).toByte()
            for (i in 0 until 8) b[i + 8] = ((low shr (56 - i * 8)) and 0xFFu).toByte()
            return b
        }

        override fun toCanonicalString(): String {
            val groups = IntArray(8)
            for (i in 0 until 4) groups[i] = ((high shr (48 - i * 16)) and 0xFFFFu).toInt()
            for (i in 0 until 4) groups[i + 4] = ((low shr (48 - i * 16)) and 0xFFFFu).toInt()
            val s = compressIpv6(groups)
            return if (scopeId != 0) "$s%$scopeId" else s
        }

        companion object {
            val ANY = V6(0u, 0u)
            val LOOPBACK = V6(0u, 1u)

            internal fun ofGroups(groups: IntArray, scopeId: Int = 0): V6 {
                require(groups.size == 8)
                var h = 0uL
                var l = 0uL
                for (i in 0 until 4) h = (h shl 16) or (groups[i].toULong() and 0xFFFFu)
                for (i in 0 until 4) l = (l shl 16) or (groups[i + 4].toULong() and 0xFFFFu)
                return V6(h, l, scopeId)
            }
        }
    }

    companion object {
        /**
         * Parses an IP literal. Returns `null` if the input is not a valid
         * IPv4 or IPv6 literal — hostnames fall through to `null` so callers
         * can decide whether to treat the string as a name instead.
         */
        fun parseOrNull(s: String): IpAddress? {
            if (s.isEmpty()) return null
            // Strip optional IPv6 brackets.
            val stripped = if (s.startsWith('[') && s.endsWith(']')) s.substring(1, s.length - 1) else s

            // Split scope suffix (%name or %index) for IPv6.
            val pct = stripped.indexOf('%')
            val (addrPart, scopeStr) = if (pct >= 0) {
                stripped.substring(0, pct) to stripped.substring(pct + 1)
            } else {
                stripped to null
            }

            // IPv4 has no colon and no scope.
            if (':' !in addrPart) {
                if (scopeStr != null) return null
                return parseIpv4OrNull(addrPart)?.let { V4(it) }
            }

            val scopeId = scopeStr?.let { resolveScopeId(it) } ?: 0
            return parseIpv6OrNull(addrPart, scopeId)
        }

        /** Parses an IP literal or throws. */
        fun parse(s: String): IpAddress = parseOrNull(s)
            ?: error("not an IP literal: $s")

        /** Constructs an IpAddress from a 4- or 16-byte array. */
        fun ofBytes(bytes: ByteArray, scopeId: Int = 0): IpAddress = when (bytes.size) {
            4 -> {
                require(scopeId == 0) { "IPv4 does not support scope id" }
                V4(
                    ((bytes[0].toInt() and 0xFF) shl 24) or
                        ((bytes[1].toInt() and 0xFF) shl 16) or
                        ((bytes[2].toInt() and 0xFF) shl 8) or
                        (bytes[3].toInt() and 0xFF),
                )
            }
            16 -> {
                var h = 0uL
                var l = 0uL
                for (i in 0 until 8) h = (h shl 8) or (bytes[i].toULong() and 0xFFu)
                for (i in 0 until 8) l = (l shl 8) or (bytes[i + 8].toULong() and 0xFFu)
                V6(h, l, scopeId)
            }
            else -> error("IP byte array must be 4 or 16 bytes, got ${bytes.size}")
        }
    }
}

// --- Parser internals (pure Kotlin, no platform deps) ---

private fun parseIpv4OrNull(s: String): Int? {
    val parts = s.split('.')
    if (parts.size != 4) return null
    var acc = 0
    for (part in parts) {
        if (part.isEmpty() || part.length > 3) return null
        // Reject leading zeros to avoid "octal-looking" parses (RFC 3986).
        if (part.length > 1 && part[0] == '0') return null
        val n = part.toIntOrNull() ?: return null
        if (n !in 0..255) return null
        acc = (acc shl 8) or n
    }
    return acc
}

private fun parseIpv6OrNull(s: String, scopeId: Int): IpAddress.V6? {
    if (s.isEmpty()) return null

    // Find optional embedded IPv4 suffix (last segment contains '.').
    val lastColon = s.lastIndexOf(':')
    val hasEmbeddedV4 = lastColon >= 0 && '.' in s.substring(lastColon + 1)

    val (hexPart, tail) = if (hasEmbeddedV4) {
        val v4Str = s.substring(lastColon + 1)
        val v4 = parseIpv4OrNull(v4Str) ?: return null
        s.substring(0, lastColon) to intArrayOf(
            (v4 ushr 16) and 0xFFFF,
            v4 and 0xFFFF,
        )
    } else {
        s to IntArray(0)
    }

    // Split around `::` (at most once).
    val doubleColon = hexPart.indexOf("::")
    val (left, right) = if (doubleColon >= 0) {
        val l = if (doubleColon == 0) {
            emptyList()
        } else {
            hexPart.substring(0, doubleColon).split(':')
        }
        val r = if (doubleColon + 2 >= hexPart.length) {
            emptyList()
        } else {
            hexPart.substring(doubleColon + 2).split(':')
        }
        l to r
    } else {
        val parts = hexPart.split(':')
        parts to emptyList()
    }

    val leftGroups = IntArray(left.size)
    for (i in left.indices) {
        leftGroups[i] = parseHexGroupOrNull(left[i]) ?: return null
    }
    val rightGroups = IntArray(right.size)
    for (i in right.indices) {
        rightGroups[i] = parseHexGroupOrNull(right[i]) ?: return null
    }

    val totalExplicit = leftGroups.size + rightGroups.size + tail.size
    if (totalExplicit > 8) return null
    if (doubleColon < 0 && totalExplicit != 8) return null
    if (doubleColon >= 0 && totalExplicit == 8) return null // :: must elide >=1 group

    val result = IntArray(8)
    leftGroups.copyInto(result, 0)
    val tailStart = 8 - tail.size
    tail.copyInto(result, tailStart)
    rightGroups.copyInto(result, tailStart - rightGroups.size)

    return IpAddress.V6.ofGroups(result, scopeId)
}

private fun parseHexGroupOrNull(s: String): Int? {
    if (s.isEmpty() || s.length > 4) return null
    var n = 0
    for (ch in s) {
        val d = when (ch) {
            in '0'..'9' -> ch - '0'
            in 'a'..'f' -> 10 + (ch - 'a')
            in 'A'..'F' -> 10 + (ch - 'A')
            else -> return null
        }
        n = (n shl 4) or d
    }
    return n
}

private fun compressIpv6(groups: IntArray): String {
    // RFC 5952: replace the longest run of zero groups (length >= 2) with `::`.
    var bestStart = -1
    var bestLen = 0
    var i = 0
    while (i < 8) {
        if (groups[i] == 0) {
            var j = i
            while (j < 8 && groups[j] == 0) j++
            val len = j - i
            if (len > bestLen) {
                bestStart = i
                bestLen = len
            }
            i = j
        } else {
            i++
        }
    }
    if (bestLen < 2) bestStart = -1

    val sb = StringBuilder()
    i = 0
    while (i < 8) {
        if (i == bestStart) {
            sb.append("::")
            i += bestLen
            continue
        }
        if (i > 0 && !(sb.endsWith("::"))) sb.append(':')
        sb.append(groups[i].toString(16))
        i++
    }
    // Edge: all zeros — bestStart=0, bestLen=8 → "::" already.
    return sb.toString().ifEmpty { "::" }
}

/**
 * Resolves the scope suffix to an interface index. Numeric literals are
 * parsed directly; interface names are platform-specific and not resolved
 * here — the parser returns `0` for unknown interface names. The actual
 * `if_nametoindex(3)` lookup happens closer to the socket boundary where
 * platform APIs are available.
 */
private fun resolveScopeId(s: String): Int = s.toIntOrNull() ?: 0
