package io.github.fukusaka.keel.client.http

import io.github.fukusaka.keel.client.http.dsl.keelHttpClient
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.server.http.dsl.keelHttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Real-socket integration for [KeelHttpClient]: a `keelHttpServer` on a
 * real [NioEngine] bound to loopback, driven by the client over real TCP.
 *
 * Unlike the in-memory `KeelHttpClientTest`, this exercises the parts the
 * in-memory double cannot: real DNS resolution (`localhost` -> `127.0.0.1`
 * through the engine's resolver) and a real `connect(2)` / accept round
 * trip. `runBlocking { withTimeout(...) }` gives a real-time deadline
 * (the real-I/O pattern).
 */
class KeelHttpClientNioIntegrationTest {

    private val budget = 15.seconds

    @Test
    fun `GET over a real socket resolves localhost and returns the body`() = runBlocking {
        withTimeout(budget) {
            val engine = NioEngine()
            val server = keelHttpServer(engine) {
                connector {
                    host = "127.0.0.1"
                    port = 0
                }
                get("/hello") { call -> call.respondText("Hello over TCP") }
                post("/echo") { call -> call.respondText(call.receiveBytes().decodeToString()) }
            }
            server.start()
            val port = (server.localAddress as InetSocketAddress).port
            try {
                val client = keelHttpClient(engine)

                // Hostname form: the engine resolver turns "localhost" into a
                // loopback IP before connect.
                val viaDns = client.get("http://localhost:$port/hello")
                assertEquals(HttpStatus.OK, viaDns.status)
                assertEquals("Hello over TCP", viaDns.bodyText())

                // IP-literal form + a request body.
                val viaIp = client.post("http://127.0.0.1:$port/echo", body = "round-trip".encodeToByteArray())
                assertEquals(HttpStatus.OK, viaIp.status)
                assertEquals("round-trip", viaIp.bodyText())
            } finally {
                server.stop()
                engine.close()
            }
        }
    }
}
