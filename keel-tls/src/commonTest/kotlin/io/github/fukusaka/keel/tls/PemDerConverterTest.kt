package io.github.fukusaka.keel.tls

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PemDerConverterTest {

    /** Self-signed RSA 2048-bit certificate (same as BenchmarkCertificates). */
    private val certPem = """
-----BEGIN CERTIFICATE-----
MIIDCTCCAfGgAwIBAgIUaVO1WKzG9gPzYk5Td3h5tNjDl0QwDQYJKoZIhvcNAQEL
BQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MB4XDTI2MDQwMzA0MjcxNloXDTI3MDQw
MzA0MjcxNlowFDESMBAGA1UEAwwJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEF
AAOCAQ8AMIIBCgKCAQEAshZok7kN0FOmy+QXXPDq4ZI0Dj/f20KYjxku2HdEcMXQ
boyY+Yh4F0Ag3YdQCa9SNwSERXKaxzQCR2FDvxR1tkx7/UFewijuvQmSLt9oqD9M
oI6+mZlwK9StE4MbuLigLoI6MGhRCzAC56ZzhH49cbS1ax4waQGaVh7/ijSz/apo
KCmoHKn1X7AuZJepnjDGwsPI0TX2m6SFAtNanH9M4Wp3uzgvlCFd7FGbwMBj+JuU
YA5cvAy/RgUPTSKjzmSAl6MN9/Uoda4qzJl0fCaZGhGxsVb9txVRCu7YTIz7MIcB
BwyphJtA0CSGa8oTJMGtUqlawGFwyOIIGJjx+CneCQIDAQABo1MwUTAdBgNVHQ4E
FgQU3Kkr9odzVo91JZso0zBsTicdW0cwHwYDVR0jBBgwFoAU3Kkr9odzVo91JZso
0zBsTicdW0cwDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAF01R
PJIlhyBh1DgS7JUbQkrhHYHvA/c25OMIQSJ8ClNJHL6yV6lrm8VIxAmPAFoNk7DX
clB3+xiZcUV0Ia1tuOgOnsouJaHQjAWdNcfweHu0mLnxRrBF/OKRDRfasN/XGrEY
xA2XszM9gkm2JrLeSt7GSfhzSykUFXDlGTiA4hExB/gCQN5Hhfkw4HXtiwsrqJTI
dA0v0c6TRwAZKuG5BIzAh9r94fM0NzYvaYamE+/WIm6orpjzUELVKjVebvmAWkN0
DckJ9HFnEw1KPYC/9e7a1JUrkfMgCFcgIdRGQA/qMHISUzQND9Zs/ZnPvhaf+x7N
wIy8X6kST+S43rMGiQ==
-----END CERTIFICATE-----
""".trimIndent() + "\n"

    /** RSA 2048-bit private key in PKCS#8 format (same as BenchmarkCertificates). */
    private val keyPem = """
-----BEGIN PRIVATE KEY-----
MIIEuwIBADANBgkqhkiG9w0BAQEFAASCBKUwggShAgEAAoIBAQCyFmiTuQ3QU6bL
5Bdc8OrhkjQOP9/bQpiPGS7Yd0RwxdBujJj5iHgXQCDdh1AJr1I3BIRFcprHNAJH
YUO/FHW2THv9QV7CKO69CZIu32ioP0ygjr6ZmXAr1K0Tgxu4uKAugjowaFELMALn
pnOEfj1xtLVrHjBpAZpWHv+KNLP9qmgoKagcqfVfsC5kl6meMMbCw8jRNfabpIUC
01qcf0zhane7OC+UIV3sUZvAwGP4m5RgDly8DL9GBQ9NIqPOZICXow339Sh1rirM
mXR8JpkaEbGxVv23FVEK7thMjPswhwEHDKmEm0DQJIZryhMkwa1SqVrAYXDI4ggY
mPH4Kd4JAgMBAAECggEAB6IQP2yqG+jJ+GlBWxl0Z9C1bHruZF55XYDN0jdidpbz
9RkPoXpo804rWnNnSdL66iLGbJeZ7Lnc8yRHHBSLaxHiKpu3rQjGGtIjMuEegj+c
UDFuF/VMqoRGGtT+xi8bpoKsbdC60IjxRu6Kev5SMeJ1+C5mEsofzFstxsW1hUTL
NvPt+RbuosMGk/uDKFMXYFxKmly6Tr2EMxMTMmtIdb2jCCDpVnXPCUyW2pv6PHu2
tbqQF/UExA1Bz6t6mIxIZieNckWbOcdH+UJyTss0//kRjUMrOg3Xu6pMtDbh679f
2Xoc+mhNkMIvcYS2AJ2713Ok5RmfLNOnj/PAhjYBJQKBgQDh2awW98zzb/FTZ3bl
lH2x/bdsiNzKGZvwxMUa3Id53f1rwHBFvw05cPsaiaaegfkhRFMJUAacTeMIUm7c
K4AZ8iJ0CxD70nzCmOoihZB7keZapNjYIGYLhlQGB5BczKfiL+rIgn5X03kvdL0G
K7uQ8tqwJZzqMWEUCIPNN8S0ewKBgQDJ3Hd3tyUHnWPHqtMDqllW+/E0lDvzDMIL
qti6SENjAWmDY4A9AVt02oSDqGXK47p96cO5/klULRSkjzoW6/54eB8ICIAnObPv
lIYTPXFoEICBCDweu63shfgE/DUE85DH0cI8dgMHsa/4Hq5QM3dCc7jDJLMvVYl8
ErJmdrWaSwJ/bkbawFw+tp7yNsdORss6lK5N4bDyHbxjaCysEXGctOSv2O0d5PBk
hKel9E9CDCNqgdPat7FbiPZ+5JFbkCWtZv3T1NWSdWNRh7Min7iX075pu9jCCMXJ
DdeJL2iCFM3ZK5g6C62sAzY+9e0KXvj7nMr3/Qpgk/mIbT+7G3kfkwKBgDObdMOb
hBENUPw0FRyjyZFuef06RJVf1qBK/nupi+jc7I/VuWxfU3VthGFwhQ246O3V/N8p
PrARkmx73ZsMnJNKCozwN2tP2kvPCfQTSlITnfbfFxe4Xb/RhFYp8JgieQpM+z6f
4ShvahCiL2h7r+rCUSM618CrOqoI0alWghk/AoGBALoo1MDASnYoh9b18siAYuA3
yGIdCqVeuv9SC0duPplXUVQwuYkLDZaIASA8goes6f5UiFEkE8TXYAKTitNUQqob
s0/JN9iAF2/A2ct6J46JuRo8bxt+LdZY2znb8weICRpxx7/Sf+lswHA7OiUJT8UG
XDEgg9dRd2akza/XK5Hj
-----END PRIVATE KEY-----
""".trimIndent() + "\n"

    @Test
    fun `pemToDer produces valid DER from certificate PEM`() {
        val der = PemDerConverter.pemToDer(certPem)
        // DER should start with SEQUENCE tag (0x30)
        assertEquals(0x30, der[0].toInt() and 0xFF)
        assertTrue(der.size > 100, "DER certificate should be substantial")
    }

    @Test
    fun `pemToDer produces valid DER from private key PEM`() {
        val der = PemDerConverter.pemToDer(keyPem)
        assertEquals(0x30, der[0].toInt() and 0xFF)
        assertTrue(der.size > 100, "DER private key should be substantial")
    }

    @Test
    fun `round-trip PEM to DER to PEM for certificate`() {
        val der = PemDerConverter.pemToDer(certPem)
        val roundTripped = PemDerConverter.derToPem(der, "CERTIFICATE")
        val derAgain = PemDerConverter.pemToDer(roundTripped)
        assertContentEquals(der, derAgain, "Round-trip should be lossless")
    }

    @Test
    fun `round-trip PEM to DER to PEM for private key`() {
        val der = PemDerConverter.pemToDer(keyPem)
        val roundTripped = PemDerConverter.derToPem(der, "PRIVATE KEY")
        val derAgain = PemDerConverter.pemToDer(roundTripped)
        assertContentEquals(der, derAgain, "Round-trip should be lossless")
    }

    @Test
    fun `invalid PEM throws for plain text`() {
        assertFailsWith<IllegalArgumentException> {
            PemDerConverter.pemToDer("not a pem")
        }
    }

    @Test
    fun `invalid PEM throws for missing END footer`() {
        val noFooter = "-----BEGIN CERTIFICATE-----\nMIIB\n"
        assertFailsWith<IllegalArgumentException> {
            PemDerConverter.pemToDer(noFooter)
        }
    }

    @Test
    fun `invalid PEM throws for missing BEGIN header`() {
        val noHeader = "MIIB\n-----END CERTIFICATE-----\n"
        assertFailsWith<IllegalArgumentException> {
            PemDerConverter.pemToDer(noHeader)
        }
    }

    @Test
    fun `invalid PEM throws for empty body`() {
        val emptyBody = "-----BEGIN CERTIFICATE-----\n-----END CERTIFICATE-----\n"
        // Only 2 lines (header + footer), fewer than the minimum 3 required
        assertFailsWith<IllegalArgumentException> {
            PemDerConverter.pemToDer(emptyBody)
        }
    }

    @Test
    fun `pemToDer tolerates whitespace in body`() {
        // Build PEM with extra blank lines / spaces in the body
        val base64Line = certPem.trim().lines().drop(1).dropLast(1).joinToString("")
        val pemWithSpaces = "-----BEGIN CERTIFICATE-----\n  $base64Line  \n-----END CERTIFICATE-----\n"
        val der = PemDerConverter.pemToDer(pemWithSpaces)
        val expected = PemDerConverter.pemToDer(certPem)
        assertContentEquals(expected, der, "Whitespace in body should be ignored")
    }

    @Test
    fun `derToPem produces correct PEM format`() {
        val der = PemDerConverter.pemToDer(certPem)
        val pem = PemDerConverter.derToPem(der, "CERTIFICATE")
        val lines = pem.trimEnd().lines()
        assertEquals("-----BEGIN CERTIFICATE-----", lines.first())
        assertEquals("-----END CERTIFICATE-----", lines.last())
        // All body lines except the last should be exactly 64 characters
        val bodyLines = lines.drop(1).dropLast(1)
        assertTrue(bodyLines.isNotEmpty(), "Should have at least one body line")
        for (line in bodyLines.dropLast(1)) {
            assertEquals(64, line.length, "Body line should be 64 chars: $line")
        }
        // Last body line can be shorter
        assertTrue(bodyLines.last().length in 1..64)
    }

    @Test
    fun `derToPem with small DER produces single body line`() {
        val smallDer = byteArrayOf(0x30, 0x03, 0x01, 0x01, 0x00)
        val pem = PemDerConverter.derToPem(smallDer, "TEST")
        val lines = pem.trimEnd().lines()
        assertEquals("-----BEGIN TEST-----", lines.first())
        assertEquals("-----END TEST-----", lines.last())
        // 5 bytes → very short Base64 → single body line
        assertEquals(3, lines.size, "Small DER should produce header + 1 body line + footer")
    }

    @Test
    fun `derToPem with empty DER`() {
        val pem = PemDerConverter.derToPem(byteArrayOf(), "EMPTY")
        val lines = pem.trimEnd().lines()
        assertEquals("-----BEGIN EMPTY-----", lines.first())
        assertEquals("-----END EMPTY-----", lines.last())
    }

    @Test
    fun `asPem returns same instance for Pem source`() {
        val pem = TlsCertificateSource.Pem(certPem, keyPem)
        val result = pem.asPem()
        assertTrue(result === pem, "asPem on Pem should return same instance")
    }

    @Test
    fun `asDer returns same instance for Der source`() {
        val der = TlsCertificateSource.Der(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val result = der.asDer()
        assertTrue(result === der, "asDer on Der should return same instance")
    }

    @Test
    fun `asPem converts Der to Pem`() {
        val der = TlsCertificateSource.Pem(certPem, keyPem).asDer()
        val pem = der.asPem()
        // Verify by round-tripping back to DER
        val derAgain = pem.asDer()
        assertContentEquals(der.certificate, derAgain.certificate)
        assertContentEquals(der.privateKey, derAgain.privateKey)
    }

    @Test
    fun `asDer converts Pem to Der`() {
        val pem = TlsCertificateSource.Pem(certPem, keyPem)
        val der = pem.asDer()
        assertTrue(der.certificate.isNotEmpty())
        assertTrue(der.privateKey.isNotEmpty())
        // DER cert should start with SEQUENCE
        assertEquals(0x30, der.certificate[0].toInt() and 0xFF)
        assertEquals(0x30, der.privateKey[0].toInt() and 0xFF)
    }

    @Test
    fun `asPem throws for KeyStoreFile`() {
        val ks = TlsCertificateSource.KeyStoreFile("path", "password")
        assertFailsWith<IllegalStateException> { ks.asPem() }
    }

    @Test
    fun `asDer throws for KeyStoreFile`() {
        val ks = TlsCertificateSource.KeyStoreFile("path", "password")
        assertFailsWith<IllegalStateException> { ks.asDer() }
    }

    @Test
    fun `asPem throws for SystemKeychain`() {
        val sk = TlsCertificateSource.SystemKeychain("label")
        assertFailsWith<IllegalStateException> { sk.asPem() }
    }

    @Test
    fun `asDer throws for SystemKeychain`() {
        val sk = TlsCertificateSource.SystemKeychain("label")
        assertFailsWith<IllegalStateException> { sk.asDer() }
    }

    @Test
    fun `asPem from Der preserves certificate content`() {
        val originalDer = PemDerConverter.pemToDer(certPem)
        val derSource = TlsCertificateSource.Pem(certPem, keyPem).asDer()
        val pemResult = derSource.asPem()
        // Re-decode to DER and compare binary content
        val roundTrippedDer = PemDerConverter.pemToDer(pemResult.certificatePem)
        assertContentEquals(originalDer, roundTrippedDer)
    }

    @Test
    fun `asDer from Pem preserves private key content`() {
        val originalKeyDer = PemDerConverter.pemToDer(keyPem)
        val derSource = TlsCertificateSource.Pem(certPem, keyPem).asDer()
        assertContentEquals(originalKeyDer, derSource.privateKey)
    }

    @Test
    fun `double conversion Pem to Der to Pem to Der is lossless`() {
        val pem = TlsCertificateSource.Pem(certPem, keyPem)
        val der1 = pem.asDer()
        val pem2 = der1.asPem()
        val der2 = pem2.asDer()
        assertContentEquals(der1.certificate, der2.certificate)
        assertContentEquals(der1.privateKey, der2.privateKey)
    }

    @Test
    fun `derToPem label is preserved in output`() {
        val der = byteArrayOf(0x30, 0x03, 0x01, 0x01, 0x00)
        val pem = PemDerConverter.derToPem(der, "RSA PRIVATE KEY")
        assertTrue(pem.startsWith("-----BEGIN RSA PRIVATE KEY-----"))
        assertTrue(pem.contains("-----END RSA PRIVATE KEY-----"))
    }

    @Test
    fun `pemToDer accepts mismatched BEGIN and END labels`() {
        // PemDerConverter does not validate label consistency — this is by design
        // since the label is not part of the DER data. Callers must validate labels
        // if they need label-specific parsing.
        val mismatchedPem = "-----BEGIN CERTIFICATE-----\nMAA=\n-----END PRIVATE KEY-----\n"
        val der = PemDerConverter.pemToDer(mismatchedPem)
        assertEquals(2, der.size, "Should decode despite mismatched labels")
    }

    @Test
    fun `pemToDer with whitespace-only body`() {
        val whitespacePem = "-----BEGIN TEST-----\n   \n-----END TEST-----\n"
        // Body is only whitespace → after regex replace, empty Base64 string → empty DER
        val der = PemDerConverter.pemToDer(whitespacePem)
        assertTrue(der.isEmpty(), "Whitespace-only body should produce empty DER")
    }

    @Test
    fun `pemToDer ignores label content`() {
        // Same binary data wrapped with different labels should produce identical DER
        val der = byteArrayOf(0x30, 0x03, 0x01, 0x01, 0x00)
        val pem1 = PemDerConverter.derToPem(der, "CERTIFICATE")
        val pem2 = PemDerConverter.derToPem(der, "PRIVATE KEY")
        assertContentEquals(
            PemDerConverter.pemToDer(pem1),
            PemDerConverter.pemToDer(pem2),
        )
        assertFalse(pem1 == pem2, "Different labels should produce different PEM strings")
    }

    // --- Line ending and encoding edge cases ---

    @Test
    fun `pemToDer handles CRLF line endings`() {
        val crlfPem = certPem.replace("\n", "\r\n")
        val der = PemDerConverter.pemToDer(crlfPem)
        val expected = PemDerConverter.pemToDer(certPem)
        assertContentEquals(expected, der, "CRLF line endings should produce same DER")
    }

    @Test
    fun `pemToDer rejects invalid Base64 in body`() {
        val invalidBase64 = "-----BEGIN TEST-----\n!!!invalid!!!\n-----END TEST-----\n"
        assertFailsWith<IllegalArgumentException> {
            PemDerConverter.pemToDer(invalidBase64)
        }
    }

    @Test
    fun `pemToDer handles minimum valid PEM with one body line`() {
        // One body line between header and footer
        val minPem = "-----BEGIN TEST-----\nMAA=\n-----END TEST-----\n"
        val der = PemDerConverter.pemToDer(minPem)
        // "MAA=" decodes to [0x30, 0x00] (empty SEQUENCE)
        assertEquals(2, der.size)
        assertEquals(0x30, der[0].toInt() and 0xFF)
        assertEquals(0x00, der[1].toInt() and 0xFF)
    }

    @Test
    fun `pemToDer handles PEM with leading and trailing whitespace`() {
        val paddedPem = "   \n\n$certPem\n\n   "
        val der = PemDerConverter.pemToDer(paddedPem)
        val expected = PemDerConverter.pemToDer(certPem)
        assertContentEquals(expected, der, "Leading/trailing whitespace should be trimmed")
    }

    // --- derToPem boundary cases ---

    @Test
    fun `derToPem with 1 byte DER`() {
        val pem = PemDerConverter.derToPem(byteArrayOf(0x42), "X")
        val lines = pem.trimEnd().lines()
        assertEquals(3, lines.size, "1 byte DER: header + 1 body line + footer")
        assertEquals("-----BEGIN X-----", lines[0])
        assertEquals("-----END X-----", lines[2])
        // Round-trip
        assertContentEquals(byteArrayOf(0x42), PemDerConverter.pemToDer(pem))
    }

    @Test
    fun `derToPem at exact 64-char Base64 boundary`() {
        // 48 bytes of DER → exactly 64 chars of Base64 (no padding) → single full line
        val der48 = ByteArray(48) { it.toByte() }
        val pem = PemDerConverter.derToPem(der48, "TEST")
        val bodyLines = pem.trimEnd().lines().drop(1).dropLast(1)
        assertEquals(1, bodyLines.size, "48 bytes should produce exactly 1 body line of 64 chars")
        assertEquals(64, bodyLines[0].length)
        // Round-trip
        assertContentEquals(der48, PemDerConverter.pemToDer(pem))
    }

    @Test
    fun `derToPem just over 64-char boundary`() {
        // 49 bytes → 68 chars of Base64 → 2 lines (64 + 4)
        val der49 = ByteArray(49) { it.toByte() }
        val pem = PemDerConverter.derToPem(der49, "TEST")
        val bodyLines = pem.trimEnd().lines().drop(1).dropLast(1)
        assertEquals(2, bodyLines.size, "49 bytes should produce 2 body lines")
        assertEquals(64, bodyLines[0].length)
        assertTrue(bodyLines[1].length < 64)
        // Round-trip
        assertContentEquals(der49, PemDerConverter.pemToDer(pem))
    }

    @Test
    fun `derToPem output ends with newline`() {
        val pem = PemDerConverter.derToPem(byteArrayOf(0x00), "X")
        assertTrue(pem.endsWith("\n"), "PEM output should end with newline")
    }

    // --- asPem / asDer error message content ---

    @Test
    fun `asPem error for KeyStoreFile contains useful message`() {
        val ex = assertFailsWith<IllegalStateException> {
            TlsCertificateSource.KeyStoreFile("test.p12", "secret").asPem()
        }
        assertTrue(ex.message!!.contains("KeyStoreFile"), "Error should mention KeyStoreFile")
    }

    @Test
    fun `asDer error for SystemKeychain contains useful message`() {
        val ex = assertFailsWith<IllegalStateException> {
            TlsCertificateSource.SystemKeychain("my-identity").asDer()
        }
        assertTrue(ex.message!!.contains("SystemKeychain"), "Error should mention SystemKeychain")
    }

    @Test
    fun `asPem error for SystemKeychain contains useful message`() {
        val ex = assertFailsWith<IllegalStateException> {
            TlsCertificateSource.SystemKeychain("my-identity").asPem()
        }
        assertTrue(ex.message!!.contains("extracted"), "Error should explain why conversion fails")
    }

    @Test
    fun `asDer error for KeyStoreFile contains useful message`() {
        val ex = assertFailsWith<IllegalStateException> {
            TlsCertificateSource.KeyStoreFile("test.p12", "secret").asDer()
        }
        assertTrue(ex.message!!.contains("KeyStore"), "Error should mention KeyStore")
    }
}
