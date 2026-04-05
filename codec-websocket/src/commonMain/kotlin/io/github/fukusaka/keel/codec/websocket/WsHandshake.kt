package io.github.fukusaka.keel.codec.websocket

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

/**
 * Computes the `Sec-WebSocket-Accept` value from a client's `Sec-WebSocket-Key`
 * header (RFC 6455 §4.2.2, step 5.4).
 */
@OptIn(ExperimentalEncodingApi::class)
fun computeAcceptKey(clientKey: String): String {
    val input = (clientKey + WS_GUID).encodeToByteArray()
    return Base64.encode(sha1(input))
}

/**
 * Returns true if [key] is a valid `Sec-WebSocket-Key` value:
 * a Base64-encoded 16-byte nonce (RFC 6455 §4.2.1, step 3).
 */
@OptIn(ExperimentalEncodingApi::class)
fun validateClientKey(key: String): Boolean {
    return try {
        val decoded = Base64.decode(key)
        decoded.size == 16
    } catch (_: Exception) {
        false
    }
}

// RFC 3174 compliant SHA-1 implementation (pure Kotlin, no external libraries)
internal fun sha1(input: ByteArray): ByteArray {
    var h0 = 0x67452301
    var h1 = 0xEFCDAB89.toInt()
    var h2 = 0x98BADCFE.toInt()
    var h3 = 0x10325476
    var h4 = 0xC3D2E1F0.toInt()

    val msgLen = input.size.toLong()
    val bitLen = msgLen * 8

    // padding: append 0x80, zeros until length ≡ 56 (mod 64), then 64-bit big-endian bit length
    val padLen = ((64 - ((msgLen + 9) % 64)) % 64).toInt()
    val padded = ByteArray((msgLen + 1 + padLen + 8).toInt())
    input.copyInto(padded)
    padded[msgLen.toInt()] = 0x80.toByte()
    for (i in 0..7) {
        padded[padded.size - 8 + i] = (bitLen shr ((7 - i) * 8)).toByte()
    }

    val w = IntArray(80)
    for (chunkStart in 0 until padded.size step 64) {
        for (i in 0..15) {
            w[i] = ((padded[chunkStart + i * 4].toInt() and 0xFF) shl 24) or
                ((padded[chunkStart + i * 4 + 1].toInt() and 0xFF) shl 16) or
                ((padded[chunkStart + i * 4 + 2].toInt() and 0xFF) shl 8) or
                (padded[chunkStart + i * 4 + 3].toInt() and 0xFF)
        }
        for (i in 16..79) {
            val x = w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]
            w[i] = x.rotateLeft(1)
        }

        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4

        for (i in 0..79) {
            val (f, k) = when {
                i < 20 -> (b and c) or (b.inv() and d) to 0x5A827999
                i < 40 -> (b xor c xor d) to 0x6ED9EBA1
                i < 60 -> (b and c) or (b and d) or (c and d) to 0x8F1BBCDC.toInt()
                else   -> (b xor c xor d) to 0xCA62C1D6.toInt()
            }
            val temp = a.rotateLeft(5) + f + e + k + w[i]
            e = d
            d = c
            c = b.rotateLeft(30)
            b = a
            a = temp
        }

        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
    }

    return ByteArray(20).also { out ->
        for (i in 0..3) {
            out[i]      = (h0 shr ((3 - i) * 8)).toByte()
            out[4 + i]  = (h1 shr ((3 - i) * 8)).toByte()
            out[8 + i]  = (h2 shr ((3 - i) * 8)).toByte()
            out[12 + i] = (h3 shr ((3 - i) * 8)).toByte()
            out[16 + i] = (h4 shr ((3 - i) * 8)).toByte()
        }
    }
}
