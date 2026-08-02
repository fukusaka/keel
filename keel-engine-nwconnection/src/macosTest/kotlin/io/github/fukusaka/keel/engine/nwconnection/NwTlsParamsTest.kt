@file:OptIn(ExperimentalForeignApi::class)

package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsTrustSource
import io.github.fukusaka.keel.tls.TlsVerifyMode
import io.github.fukusaka.keel.tls.TlsVersion
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Smoke coverage for [NwTlsParams.createTlsParameters]: exercises every
 * server-relevant axis of [TlsConfig] and pins that the C wrapper
 * accepts the resulting call — i.e. every setting funnels through
 * `keel_nw_create_tls_tcp_params_full` without the Security framework
 * or Network framework rejecting the composed options.
 *
 * Handshake-level Red-Green (that these settings actually reach the
 * wire and reject non-conforming peers) lives in
 * [NwListenerTlsIntegrationTest], which stands up a real NWListener and
 * an NWConnection client against it. This file stays a pure unit smoke
 * — it does not open sockets.
 */
class NwTlsParamsTest {

    /** Self-signed RSA 2048-bit certificate, CN=localhost. */
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

    /** Second, unrelated CA to exercise the pinned trust anchor path. */
    private val clientCaPem = """
-----BEGIN CERTIFICATE-----
MIIDHTCCAgWgAwIBAgIUMew2SLfE51igIDq864OnJUAi4HQwDQYJKoZIhvcNAQEL
BQAwHjEcMBoGA1UEAwwTa2VlbC10ZXN0LWNsaWVudC1jYTAeFw0yNjA1MzAwMjM5
MjRaFw0zNjA1MjcwMjM5MjRaMB4xHDAaBgNVBAMME2tlZWwtdGVzdC1jbGllbnQt
Y2EwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDBlr+ES7+qv1GbDCQq
fAs4nIa9zv4ZR2xyHoo+qPdd3rLdDkG0Un0/xtzomF98zMIJ/C5bt4CyCm8cHkvc
DHXgrn/XaTwqYO2TwmV8dvziNls2anCNdH3xpn/LWQ/hYEyFOaErzfpwGYeVSxNN
IRV54Z3ORHVVnCRWO7mLFlCp7Sz2nOt0PM8kLSQNuQuvc59rNrqnavaEeMDhZE9k
XiO1rJ3xEvpRheaxigFt/2URTd8CQasrWaVh3nHUv61ydEMf7/zgKBNFvXOhrg4A
B7/BNKe51249p5ymAphvFzhmbGSBBjvJ7AZRHnLQEnTwI5ZMKjqDlt0P4oTZi1DX
DsiZAgMBAAGjUzBRMB0GA1UdDgQWBBRv40oD3eCEDhe8OAXi9QGL8BtWzTAfBgNV
HSMEGDAWgBRv40oD3eCEDhe8OAXi9QGL8BtWzTAPBgNVHRMBAf8EBTADAQH/MA0G
CSqGSIb3DQEBCwUAA4IBAQBsb2FJUQDcZvHRSoch+AwWDGtmmmWYA6B4y9y5yMpb
cRe73tY7PypLPWe0Dwl/dp87y0Q76ChHxnt5Ucv7aCwlQDjivOXMPZPEIPo5BpVv
a7omISym2O+UA/9pB8hY8P+d46WBnm+qPelNbR3x1mbgTyddPW2pg60IRrrQBYeS
sRvtg6+FkAt/W3iR8aJ9rQddvLnM5tYK8xSd/GiE3XS6XOpImgcierzIgZaaDCif
c+2EM1rwl0DuKVcfOjOLlwSk9rYPwoZSphhCV2xKs8gEAP7wrJ9Coe/5u0mEdeo1
F1n/hcr7SImvdjcT85WEDf/dfkBK9dvTRZN+DFC5s3Zg
-----END CERTIFICATE-----
    """.trimIndent() + "\n"

    private val serverCerts = TlsCertificateSource.Pem(certPem, keyPem)

    // --- Smoke: every axis composes cleanly ---

    @Test
    fun `default TlsConfig produces valid parameters`() {
        val params = NwTlsParams.createTlsParameters(TlsConfig(certificates = serverCerts))
        assertNotNull(params, "default TlsConfig must produce valid parameters")
    }

    @Test
    fun `alpnProtocols composes without error`() {
        val params = NwTlsParams.createTlsParameters(
            TlsConfig(
                certificates = serverCerts,
                alpnProtocols = listOf("h2", "http/1.1"),
            ),
        )
        assertNotNull(params)
    }

    @Test
    fun `min and max version composes without error`() {
        val params = NwTlsParams.createTlsParameters(
            TlsConfig(
                certificates = serverCerts,
                minVersion = TlsVersion.TLS1_3,
                maxVersion = TlsVersion.TLS1_3,
            ),
        )
        assertNotNull(params)
    }

    @Test
    fun `verifyMode REQUIRED with pinned trust anchors composes without error`() {
        val params = NwTlsParams.createTlsParameters(
            TlsConfig(
                certificates = serverCerts,
                verifyMode = TlsVerifyMode.REQUIRED,
                trustAnchors = TlsTrustSource.Pem(clientCaPem),
            ),
        )
        assertNotNull(params, "mTLS with a pinned CA anchor must compose without error")
    }

    @Test
    fun `verifyMode NONE without trustAnchors composes without error`() {
        val params = NwTlsParams.createTlsParameters(
            TlsConfig(
                certificates = serverCerts,
                verifyMode = TlsVerifyMode.NONE,
            ),
        )
        assertNotNull(params)
    }

    // --- Failure cases ---

    @Test
    fun `missing certificates throws`() {
        assertFailsWith<IllegalArgumentException> {
            NwTlsParams.createTlsParameters(TlsConfig(certificates = null))
        }
    }
}
