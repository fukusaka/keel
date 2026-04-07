package io.github.fukusaka.keel.tls.jsse

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test

/**
 * Traces every SSLEngineResult during a TLS handshake to observe
 * the actual behavior of NEED_TASK — specifically whether
 * bytesConsumed > 0 when handshakeStatus is NEED_TASK.
 *
 * This is a diagnostic test, not a regression test.
 */
class SslEngineHandshakeTraceTest {

    @Test
    fun `trace SSLEngine handshake results`() {
        val sslContext = buildTestSslContext()
        val server = sslContext.createSSLEngine().apply { useClientMode = false }
        val client = sslContext.createSSLEngine("localhost", 443).apply { useClientMode = true }

        val bufSize = 32768
        val cToS = ByteBuffer.allocate(bufSize) // client -> server (network)
        val sToC = ByteBuffer.allocate(bufSize) // server -> client (network)
        val clientApp = ByteBuffer.allocate(bufSize)
        val serverApp = ByteBuffer.allocate(bufSize)
        val empty = ByteBuffer.allocate(0)

        server.beginHandshake()
        client.beginHandshake()

        var step = 0
        val maxSteps = 100

        while (step < maxSteps) {
            step++
            val clientHs = client.handshakeStatus
            val serverHs = server.handshakeStatus

            if (clientHs == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING &&
                serverHs == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
            ) {
                println("=== Handshake complete at step $step ===")
                break
            }

            // Client side
            when (clientHs) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val r = client.wrap(empty, cToS)
                    println("[step=$step] client.wrap:   ${fmt(r)}")
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    sToC.flip()
                    val r = client.unwrap(sToC, clientApp)
                    println("[step=$step] client.unwrap: ${fmt(r)}")
                    sToC.compact()
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    println("[step=$step] client: running delegated tasks")
                    runTasks(client)
                    println("[step=$step] client: after tasks -> engine.hs=${client.handshakeStatus}")
                }
                else -> {}
            }

            // Server side
            val serverHs2 = server.handshakeStatus
            when (serverHs2) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val r = server.wrap(empty, sToC)
                    println("[step=$step] server.wrap:   ${fmt(r)}")
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    cToS.flip()
                    val r = server.unwrap(cToS, serverApp)
                    println("[step=$step] server.unwrap: ${fmt(r)}")
                    cToS.compact()
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    println("[step=$step] server: running delegated tasks")
                    runTasks(server)
                    println("[step=$step] server: after tasks -> engine.hs=${server.handshakeStatus}")
                }
                else -> {}
            }
        }

        check(step < maxSteps) { "Handshake did not complete within $maxSteps steps" }
    }

    private fun fmt(r: SSLEngineResult): String =
        "status=${r.status} hs=${r.handshakeStatus} consumed=${r.bytesConsumed()} produced=${r.bytesProduced()}"

    private fun runTasks(engine: SSLEngine) {
        while (true) {
            val task = engine.delegatedTask ?: break
            task.run()
        }
    }

    private fun buildTestSslContext(): SSLContext {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)

        val cf = CertificateFactory.getInstance("X.509")
        val certChain = cf.generateCertificates(
            ByteArrayInputStream(TestCertificates.SERVER_CERT.toByteArray()),
        ).toTypedArray()

        val keyBase64 = TestCertificates.SERVER_KEY.lines()
            .filter { !it.startsWith("-----") }
            .joinToString("")
        val keyDer = Base64.getDecoder().decode(keyBase64)
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyDer))

        ks.setKeyEntry("test", privateKey, charArrayOf(), certChain)

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, charArrayOf())

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, tmf.trustManagers, null)
        return ctx
    }
}
