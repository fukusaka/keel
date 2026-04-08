package io.github.fukusaka.keel.tls

/**
 * Extracts the inner algorithm-specific private key from a PKCS#8 envelope.
 *
 * PKCS#8 (`BEGIN PRIVATE KEY`) wraps PKCS#1 (RSA) or SEC 1 (EC) keys:
 * ```
 * SEQUENCE {                          -- PrivateKeyInfo
 *   INTEGER 0                         -- version
 *   SEQUENCE {                        -- AlgorithmIdentifier
 *     OID <algorithm>                 -- rsaEncryption / ecPublicKey
 *     [algorithm parameters]
 *   }
 *   OCTET STRING {                    -- privateKey
 *     <PKCS#1 RSAPrivateKey or SEC 1 ECPrivateKey>
 *   }
 * }
 * ```
 *
 * Apple's `SecKeyCreateWithData` requires the inner key (PKCS#1 / SEC 1),
 * not the PKCS#8 wrapper. This utility performs the minimal ASN.1 DER
 * parsing needed to extract it.
 *
 * @see [RFC 5958](https://datatracker.ietf.org/doc/html/rfc5958) — PKCS#8
 * @see [RFC 8017](https://datatracker.ietf.org/doc/html/rfc8017) — PKCS#1 (RSA)
 * @see [RFC 5915](https://datatracker.ietf.org/doc/html/rfc5915) — SEC 1 (EC)
 */
object Pkcs8KeyUnwrapper {

    /** ASN.1 tag for SEQUENCE (0x30). */
    private const val TAG_SEQUENCE: Int = 0x30

    /** ASN.1 tag for OCTET STRING (0x04). */
    private const val TAG_OCTET_STRING: Int = 0x04

    /** ASN.1 tag for INTEGER (0x02). */
    private const val TAG_INTEGER: Int = 0x02

    /**
     * Detected key algorithm from the PKCS#8 AlgorithmIdentifier.
     */
    enum class KeyAlgorithm {
        /** RSA (OID 1.2.840.113549.1.1.1). */
        RSA,

        /** Elliptic Curve (OID 1.2.840.10045.2.1). */
        EC,

        /** Unknown algorithm. */
        UNKNOWN,
    }

    /**
     * Result of unwrapping a PKCS#8 private key.
     *
     * @param innerKey The algorithm-specific private key (PKCS#1 or SEC 1 DER).
     * @param algorithm The detected key algorithm.
     */
    data class UnwrapResult(
        val innerKey: ByteArray,
        val algorithm: KeyAlgorithm,
    )

    /**
     * Extracts the inner private key from a PKCS#8 DER-encoded envelope.
     *
     * @param pkcs8Der PKCS#8 DER bytes (`BEGIN PRIVATE KEY` content).
     * @return The inner key bytes and detected algorithm.
     * @throws IllegalArgumentException if the DER structure is invalid.
     */
    fun unwrap(pkcs8Der: ByteArray): UnwrapResult {
        var offset = 0

        // Outer SEQUENCE (PrivateKeyInfo)
        offset = expectTag(pkcs8Der, offset, TAG_SEQUENCE)
        offset = skipLength(pkcs8Der, offset)

        // version INTEGER
        offset = expectTag(pkcs8Der, offset, TAG_INTEGER)
        val versionLen = readLength(pkcs8Der, offset)
        val versionStart = skipLength(pkcs8Der, offset)
        requireBounds(versionStart, versionLen.value, pkcs8Der.size, "Version INTEGER")
        offset = versionStart + versionLen.value

        // AlgorithmIdentifier SEQUENCE
        offset = expectTag(pkcs8Der, offset, TAG_SEQUENCE)
        val algoLen = readLength(pkcs8Der, offset)
        val algoStart = skipLength(pkcs8Der, offset)
        requireBounds(algoStart, algoLen.value, pkcs8Der.size, "AlgorithmIdentifier")
        val algorithm = detectAlgorithm(pkcs8Der, algoStart, algoLen.value)
        offset = algoStart + algoLen.value

        // privateKey OCTET STRING
        offset = expectTag(pkcs8Der, offset, TAG_OCTET_STRING)
        val keyLen = readLength(pkcs8Der, offset)
        val keyStart = skipLength(pkcs8Der, offset)
        requireBounds(keyStart, keyLen.value, pkcs8Der.size, "OCTET STRING")
        val innerKey = pkcs8Der.copyOfRange(keyStart, keyStart + keyLen.value)
        return UnwrapResult(innerKey, algorithm)
    }

    /**
     * Checks if DER bytes appear to be a PKCS#8 structure.
     *
     * Performs a lightweight check: outer SEQUENCE → INTEGER version → SEQUENCE.
     * Does not fully validate the structure. Never throws — returns `false`
     * for any malformed input.
     */
    fun isPkcs8(der: ByteArray): Boolean {
        if (der.size < 10) return false
        return try {
            isPkcs8Internal(der)
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun isPkcs8Internal(der: ByteArray): Boolean {
        var offset = 0

        // Outer SEQUENCE
        if (der[offset].toInt() and 0xFF != TAG_SEQUENCE) return false
        offset++
        val outerLen = readLength(der, offset)
        offset = offset + outerLen.headerBytes

        // version INTEGER
        if (offset >= der.size || der[offset].toInt() and 0xFF != TAG_INTEGER) return false
        offset++
        val versionLen = readLength(der, offset)
        // Use subtraction to avoid integer overflow
        if (versionLen.value > der.size - (offset + versionLen.headerBytes)) return false
        offset = offset + versionLen.headerBytes + versionLen.value

        // AlgorithmIdentifier SEQUENCE
        return offset < der.size && der[offset].toInt() and 0xFF == TAG_SEQUENCE
    }

    private fun expectTag(data: ByteArray, offset: Int, expectedTag: Int): Int {
        require(offset < data.size) { "Unexpected end of DER data at offset $offset" }
        val actualTag = data[offset].toInt() and 0xFF
        require(actualTag == expectedTag) {
            "Expected ASN.1 tag 0x${expectedTag.toString(16)} at offset $offset, " +
                "got 0x${actualTag.toString(16)}"
        }
        return offset + 1
    }

    /**
     * Reads a DER length field starting at [offset].
     *
     * @return The decoded length value and the number of header bytes consumed.
     */
    private fun readLength(data: ByteArray, offset: Int): DerLength {
        require(offset < data.size) { "Unexpected end of DER data at offset $offset" }
        val first = data[offset].toInt() and 0xFF
        if (first < 0x80) {
            return DerLength(first, headerBytes = 1)
        }
        val numBytes = first and 0x7F
        require(numBytes in 1..4) { "Unsupported DER length encoding: $numBytes bytes" }
        require(offset + 1 + numBytes <= data.size) { "Truncated DER length at offset $offset" }
        var length = 0
        for (i in 0 until numBytes) {
            length = (length shl 8) or (data[offset + 1 + i].toInt() and 0xFF)
        }
        require(length >= 0) { "DER length overflow at offset $offset" }
        return DerLength(length, headerBytes = 1 + numBytes)
    }

    private fun skipLength(data: ByteArray, offset: Int): Int {
        val len = readLength(data, offset)
        return offset + len.headerBytes
    }

    private fun detectAlgorithm(data: ByteArray, offset: Int, length: Int): KeyAlgorithm {
        // AlgorithmIdentifier: SEQUENCE { OID, [params] }
        // Look for known OIDs within the AlgorithmIdentifier body.
        val algoBytes = data.copyOfRange(offset, offset + length)

        // OID for rsaEncryption: 06 09 2A 86 48 86 F7 0D 01 01 01
        val rsaOid = byteArrayOf(
            0x06, 0x09,
            0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01,
        )
        if (containsSequence(algoBytes, rsaOid)) return KeyAlgorithm.RSA

        // OID for ecPublicKey: 06 07 2A 86 48 CE 3D 02 01
        val ecOid = byteArrayOf(
            0x06, 0x07,
            0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01,
        )
        if (containsSequence(algoBytes, ecOid)) return KeyAlgorithm.EC

        return KeyAlgorithm.UNKNOWN
    }

    private fun containsSequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.size > haystack.size) return false
        for (i in 0..haystack.size - needle.size) {
            var match = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }

    /**
     * Checks that [start] + [length] does not exceed [dataSize],
     * using subtraction to avoid integer overflow.
     */
    private fun requireBounds(start: Int, length: Int, dataSize: Int, field: String) {
        // dataSize - start is safe because start <= dataSize (validated by prior readLength/expectTag)
        require(length <= dataSize - start) {
            "$field length $length exceeds remaining data at offset $start"
        }
    }

    /** DER length field value and the number of bytes consumed by the length encoding. */
    private data class DerLength(val value: Int, val headerBytes: Int)
}
