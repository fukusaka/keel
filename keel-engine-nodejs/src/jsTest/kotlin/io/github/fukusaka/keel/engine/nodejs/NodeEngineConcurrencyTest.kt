package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.core.InetSocketAddress
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class NodeEngineConcurrencyTest {

    /**
     * Regression for the pre-fix bug where multiple concurrent `accept()`
     * callers on `NodeStreamServer` overwrote each other in the
     * single-slot `pendingAcceptCont`. PR #370 replaced it with
     * `pendingAcceptConts: ArrayDeque<...>` so each caller gets its own
     * slot in FIFO order.
     *
     * JS is single-threaded; the bug requires two `accept()` to suspend
     * back-to-back (both find `pendingConnections` empty and assign the
     * single slot before either is resumed by `onConnection`). The test
     * forces this by launching N accept coroutines first (each suspends
     * immediately because no client yet), then connecting N clients —
     * `onConnection` then needs to resume each accept in turn.
     */
    @Test
    fun multipleConcurrentAcceptsAreFifoQueued() = runTest(timeout = 15.seconds) {
        val engine = NodeEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val acceptJobs = (1..8).map {
            async { server.accept() }
        }
        // Yield so the accept coroutines suspend before any connect.
        yield()

        val clientJobs = (1..8).map {
            async { engine.connect("127.0.0.1", port) }
        }

        val serverChannels = acceptJobs.awaitAll()
        val clientChannels = clientJobs.awaitAll()

        assertEquals(8, serverChannels.size)
        assertEquals(8, clientChannels.size)
        serverChannels.forEach { assertTrue(it.isOpen) }
        clientChannels.forEach { assertTrue(it.isOpen) }

        serverChannels.forEach { it.close() }
        clientChannels.forEach { it.close() }
        server.close()
        engine.close()
    }
}
