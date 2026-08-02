package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.core.InetSocketAddress
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
class IoUringEngineConnectTest {

    @Test
    fun `connect creates active channel`() = runBlocking {
        val engine = IoUringEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val accepted = CompletableDeferred<io.github.fukusaka.keel.core.Channel>()
        launch { accepted.complete(server.accept()) }

        val client = withTimeout(IO_OP_TIMEOUT_MS) { engine.connect("127.0.0.1", port) }
        assertTrue(client.isOpen)
        assertTrue(client.isActive)

        val serverCh = withTimeout(IO_OP_TIMEOUT_MS) { accepted.await() }
        serverCh.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun `connect via hostname resolves through SystemDnsResolver`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = IoUringEngine()
            // 'localhost' comes from /etc/hosts, so getaddrinfo never leaves
            // the machine — this exercises the whole resolve + connect path
            // without depending on network DNS.
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val channel = engine.connect("localhost", port)
            channel.close()

            server.close()
            engine.close()
        }
    }

    @Test
    fun `connect to a refused port throws`() = runBlocking {
        val engine = IoUringEngine()
        // Connect straight to REFUSED_PORT — a fixed non-ephemeral port
        // nothing listens on — so the refusal is deterministic (see the
        // REFUSED_PORT KDoc for why a freed ephemeral port is unsafe here).
        val ex = assertFailsWith<IllegalStateException> {
            withTimeout(IO_OP_TIMEOUT_MS) {
                engine.connect("127.0.0.1", REFUSED_PORT)
            }
        }
        // A real refusal is a plain IllegalStateException("connect() failed: …").
        // If the hang guard fires instead, withTimeout throws a
        // TimeoutCancellationException — a CancellationException, and on
        // Kotlin/Native a subtype of IllegalStateException, so the
        // assertFailsWith above does NOT screen it out. Rethrow it so a
        // starved-CI hang fails loudly as a timeout instead of slipping
        // through to the message check below with a "Timed out…" string
        // (the same intermittent linuxX64 CI failure seen on the epoll engine).
        if (ex is CancellationException) throw ex
        assertTrue(ex.message?.contains("connect") == true, "got: ${ex.message}")

        engine.close()
    }
}
