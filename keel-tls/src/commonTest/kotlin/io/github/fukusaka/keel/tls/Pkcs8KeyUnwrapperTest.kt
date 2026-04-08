package io.github.fukusaka.keel.tls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Pkcs8KeyUnwrapperTest {

    /** RSA 2048-bit private key in PKCS#8 PEM (same as BenchmarkCertificates). */
    private val pkcs8KeyPem = """
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
    fun `isPkcs8 detects PKCS8 structure`() {
        val der = PemDerConverter.pemToDer(pkcs8KeyPem)
        assertTrue(Pkcs8KeyUnwrapper.isPkcs8(der), "Should detect PKCS#8 structure")
    }

    @Test
    fun `isPkcs8 rejects non-PKCS8 data`() {
        // A PKCS#1 RSA key starts with SEQUENCE { INTEGER, INTEGER, ... }
        // but the second element is INTEGER, not SEQUENCE (AlgorithmIdentifier).
        // Use certificate DER which has different structure.
        val certDer = PemDerConverter.pemToDer("""
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
""".trimIndent() + "\n")
        // X.509 certificate has SEQUENCE { SEQUENCE { ... }, ... } but
        // the inner structure differs from PKCS#8 (no version INTEGER first).
        // isPkcs8 checks SEQUENCE → INTEGER → SEQUENCE pattern.
        assertFalse(Pkcs8KeyUnwrapper.isPkcs8(certDer), "X.509 certificate should not be detected as PKCS#8")
    }

    @Test
    fun `unwrap extracts RSA inner key from PKCS8`() {
        val pkcs8Der = PemDerConverter.pemToDer(pkcs8KeyPem)
        val result = Pkcs8KeyUnwrapper.unwrap(pkcs8Der)

        assertEquals(Pkcs8KeyUnwrapper.KeyAlgorithm.RSA, result.algorithm)
        // Inner key should be smaller than PKCS#8 (no AlgorithmIdentifier wrapper)
        assertTrue(result.innerKey.size < pkcs8Der.size)
        assertTrue(result.innerKey.size > 100, "Inner RSA key should be substantial")
        // Inner key should start with SEQUENCE (PKCS#1 RSAPrivateKey)
        assertEquals(0x30, result.innerKey[0].toInt() and 0xFF)
    }

    @Test
    fun `unwrap rejects truncated data`() {
        assertFailsWith<IllegalArgumentException> {
            Pkcs8KeyUnwrapper.unwrap(byteArrayOf(0x30, 0x03, 0x02, 0x01))
        }
    }

    @Test
    fun `isPkcs8 rejects too-short data`() {
        assertTrue(!Pkcs8KeyUnwrapper.isPkcs8(byteArrayOf(0x30, 0x01)))
    }
}
