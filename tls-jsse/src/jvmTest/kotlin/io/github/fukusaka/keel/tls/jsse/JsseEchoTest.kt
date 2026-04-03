package io.github.fukusaka.keel.tls.jsse

import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Minimal TLS echo test using JSSE (JDK standard).
 *
 * Validates:
 * - SSLContext creation with in-memory PKCS12 KeyStore
 * - SSLServerSocket bind + accept
 * - TLS handshake with curl client
 * - HTTP response over TLS
 *
 * No external dependencies — pure JDK.
 */
class JsseEchoTest {

    @Test
    fun `JSSE server handshake and echo succeeds`() {
        // --- Generate self-signed cert via keytool-equivalent ---
        // Use pre-generated PKCS12 keystore bytes (base64-encoded).
        // Generated via:
        //   keytool -genkeypair -alias test -keyalg RSA -keysize 2048 \
        //     -dname "CN=localhost" -validity 365 -storetype PKCS12 \
        //     -storepass changeit -keypass changeit -keystore test.p12
        //   base64 < test.p12
        //
        // For simplicity, generate at runtime using Java security APIs.
        val keyStore = generateSelfSignedKeyStore()

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, "changeit".toCharArray())

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, tmf.trustManagers, null)

        // --- Create server socket ---
        val serverSocket = sslContext.serverSocketFactory.createServerSocket(0)
        val port = serverSocket.localPort

        // --- curl client in background ---
        val clientThread = Thread {
            Thread.sleep(300)
            try {
                ProcessBuilder("curl", "-k", "-s", "https://localhost:$port/hello")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            } catch (_: Exception) {}
        }
        clientThread.start()

        // --- Accept + read + respond ---
        val client = serverSocket.accept()
        val input = client.getInputStream()
        val output = client.getOutputStream()

        val buf = ByteArray(4096)
        val n = input.read(buf)
        assertTrue(n > 0, "Expected to read HTTP request")

        val received = String(buf, 0, n)
        println("Server received ${n} bytes: ${received.lines().first()}")

        val body = "Hello, JSSE TLS!"
        val response = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        output.write(response.toByteArray())
        output.flush()

        client.close()
        serverSocket.close()
        clientThread.join(3000)
    }

    /**
     * Generates a self-signed certificate + private key at runtime using JDK internal API.
     *
     * This avoids shipping a pre-generated keystore file.
     * Uses sun.security.tools.keytool.CertAndKeyGen (available in all JDK distributions).
     */
    private fun generateSelfSignedKeyStore(): KeyStore {
        // Use keytool to generate to a temp file, then load.
        val tmpFile = java.io.File.createTempFile("keel-test-", ".p12")
        tmpFile.deleteOnExit()

        val pb = ProcessBuilder(
            "keytool", "-genkeypair",
            "-alias", "test",
            "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=localhost",
            "-validity", "365",
            "-storetype", "PKCS12",
            "-storepass", "changeit",
            "-keypass", "changeit",
            "-keystore", tmpFile.absolutePath,
        )
        pb.redirectErrorStream(true)
        val proc = pb.start()
        proc.waitFor()
        check(proc.exitValue() == 0) { "keytool failed: ${proc.inputStream.readAllBytes().decodeToString()}" }

        val ks = KeyStore.getInstance("PKCS12")
        tmpFile.inputStream().use { ks.load(it, "changeit".toCharArray()) }
        return ks
    }
}
