package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.InetSocketAddress
import kotlin.test.Test

/**
 * Closing a listener must release its port promptly. The JDK defers the
 * kernel-level close of a selector-registered channel until the
 * selector's next selection operation processes the cancelled key — and
 * closing a channel does not wake a blocked select — so without an
 * explicit wakeup an idle boss loop keeps a closed listener's port bound
 * until the next unrelated event. Both server variants (pipeline mode
 * and coroutine mode) are covered; the port is asserted released by
 * claiming it with a raw `ServerSocket` under a bounded retry (see
 * [assertPortReleased]), so a genuinely lingering listener still fails
 * when the budget is exhausted.
 */
class NioListenerCloseReleaseTest {

    @Test
    fun `closing a pipelined listener releases its port promptly`() = runTest {
        val engine = NioEngine()
        try {
            val server = engine.bindPipeline(InetSocketAddress("127.0.0.1", 0)) { }
            val port = (server.localAddress as InetSocketAddress).port
            server.close()
            assertPortReleased(port)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `closing a coroutine-mode listener releases its port promptly`() = runTest {
        val engine = NioEngine()
        try {
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port
            server.close()
            assertPortReleased(port)
        } finally {
            engine.close()
        }
    }
}
