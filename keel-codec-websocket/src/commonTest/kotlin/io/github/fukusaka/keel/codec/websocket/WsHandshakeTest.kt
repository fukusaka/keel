package io.github.fukusaka.keel.codec.websocket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WsHandshakeTest {

    // RFC 6455 Appendix C test vector
    @Test
    fun computeAcceptKeyKnownVector() {
        val clientKey = "dGhlIHNhbXBsZSBub25jZQ=="
        val expected  = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
        assertEquals(expected, computeAcceptKey(clientKey))
    }

    @Test
    fun computeAcceptKeyDifferentKey() {
        val key1 = "dGhlIHNhbXBsZSBub25jZQ=="  // "the sample nonce" (16 bytes)
        val key2 = "AAECAwQFBgcICQoLDA0ODw=="    // 0x00..0x0F (16 bytes)
        assertNotEquals(computeAcceptKey(key1), computeAcceptKey(key2))
    }

    @Test
    fun validateClientKeyValid() {
        // "the sample nonce" = 16 bytes → valid
        assertTrue(validateClientKey("dGhlIHNhbXBsZSBub25jZQ=="))
        // 0x00..0x0F = 16 bytes → valid
        assertTrue(validateClientKey("AAECAwQFBgcICQoLDA0ODw=="))
    }

    @Test
    fun validateClientKeyInvalidNotBase64() {
        assertFalse(validateClientKey("not-base64!!"))
        assertFalse(validateClientKey("!!!"))
    }

    @Test
    fun validateClientKeyInvalidWrongLength() {
        // Base64 of 15 bytes (too short)
        assertFalse(validateClientKey("aGVsbG8gd29ybGQ="))
    }

    @Test
    fun sha1KnownVector() {
        // SHA-1("abc") = a9993e364706816aba3e25717850c26c9cd0d89d
        val result = sha1("abc".encodeToByteArray())
        val hex = result.joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", hex)
    }

    @Test
    fun sha1EmptyInput() {
        // SHA-1("") = da39a3ee5e6b4b0d3255bfef95601890afd80709
        val result = sha1(ByteArray(0))
        val hex = result.joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", hex)
    }
}
