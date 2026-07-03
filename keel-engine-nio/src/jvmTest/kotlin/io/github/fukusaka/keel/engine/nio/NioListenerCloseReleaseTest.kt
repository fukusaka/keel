package io.github.fukusaka.keel.engine.nio

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Closing a listener must release its port promptly. The JDK defers the
 * kernel-level close of a selector-registered channel until the
 * selector's next selection operation processes the cancelled key — and
 * closing a channel does not wake a blocked select — so without an
 * explicit wakeup an idle boss loop keeps a closed listener's port bound
 * until the next unrelated event. Both server variants (pipeline mode
 * and coroutine mode) are covered; the port is asserted released by
 * claiming it with a raw [ServerSocket] under a bounded retry, so a
 * genuinely lingering listener still fails when the budget is exhausted.
 */
class NioListenerCloseReleaseTest {

    /** Claims [port] with a raw ServerSocket, retrying up to [budgetMillis]. */
    private fun assertPortReleased(port: Int, budgetMillis: Long = RELEASE_BUDGET_MS) {
        val deadline = System.currentTimeMillis() + budgetMillis
        var last: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                ServerSocket(port, 1, InetAddress.getLoopbackAddress()).close()
                return
            } catch (e: java.net.BindException) {
                last = e
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
        fail("port $port still bound ${budgetMillis}ms after the listener was closed", last)
    }

    @Test
    fun `closing a pipelined listener releases its port promptly`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = NioEngine()
            try {
                val server = engine.bindPipeline(
                    io.github.fukusaka.keel.core.InetSocketAddress("127.0.0.1", 0),
                ) { }
                val port = (server.localAddress as io.github.fukusaka.keel.core.InetSocketAddress).port
                server.close()
                assertPortReleased(port)
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun `closing a coroutine-mode listener releases its port promptly`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = NioEngine()
            try {
                val server = engine.bind("127.0.0.1", 0)
                val port = (server.localAddress as io.github.fukusaka.keel.core.InetSocketAddress).port
                server.close()
                assertPortReleased(port)
            } finally {
                engine.close()
            }
        }
    }

    private companion object {
        /** Retry budget for the port claim — generous against loaded CI runners. */
        const val RELEASE_BUDGET_MS = 2_000L

        /** Poll step between claim attempts. */
        const val POLL_INTERVAL_MS = 20L
    }
}
