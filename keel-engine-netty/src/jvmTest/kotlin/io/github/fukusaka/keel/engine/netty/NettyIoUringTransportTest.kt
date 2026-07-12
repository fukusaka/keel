package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [NettyTransport.IoUring].
 *
 * `IoUring.isAvailable()` gates whether the platform actually supports
 * io_uring (Linux 5.1+ with the `netty-transport-native-io_uring`
 * classifier on the classpath) — false everywhere else, including macOS
 * CI. [requireAvailableFailsFastWhenUnavailable] runs unconditionally and
 * is the only assertion this file can make on non-Linux hosts; the
 * echo / peer-FIN round trips only run where io_uring is actually usable
 * (Linux CI / the Linux gate host), skipping (not failing) elsewhere — same shape
 * as the platform-availability guards on [NettyTransport.Epoll] /
 * [NettyTransport.KQueue], which have no dedicated per-transport
 * integration test either (only exercised via [NettyTransport.Auto] on
 * whichever host CI runs on). The peer-FIN test mirrors
 * `NettyPeerCloseWithDisabledReadTest`'s pattern (that file exercises
 * [NettyTransport.Auto], which never resolves to [NettyTransport.IoUring]
 * — this file pins the same contract for io_uring's `POLLRDHUP` path
 * specifically, verified by reading `AbstractIoUringChannel`'s source:
 * `schedulePollRdHup()` is armed independently of the read path and
 * `autoReadCleared()` never cancels it).
 */
class NettyIoUringTransportTest {

    @Test
    fun nameIsIoUring() {
        assertEquals("io_uring", NettyTransport.IoUring.name)
    }

    @Test
    fun requireAvailableFailsFastWhenUnavailable() {
        if (io.netty.channel.uring.IoUring.isAvailable()) return
        assertFailsWith<IllegalStateException> { NettyTransport.IoUring.requireAvailable() }
    }

    @Test
    fun echoRoundTripWhenAvailable() = runTest {
        if (!io.netty.channel.uring.IoUring.isAvailable()) return@runTest

        val engine = NettyEngine(nettyTransport = NettyTransport.IoUring)
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = connectRawClient(port)
        val serverCh = server.accept()

        rawWrite(client, "hello")

        val readBuf = DefaultAllocator.allocate(64)
        val n = serverCh.read(readBuf)
        assertEquals(5, n)

        serverCh.write(readBuf)
        serverCh.flush()

        val echo = rawRead(client, 5)
        assertEquals("hello", echo)

        serverCh.close()
        client.close()
        server.close()
        engine.close()
    }

    @Test
    fun peerFinFiresOnReadClosedWhenReadEnabledStaysFalse() = runTest {
        if (!io.netty.channel.uring.IoUring.isAvailable()) return@runTest

        // Real-time wall-clock context, matching NettyPeerCloseWithDisabledReadTest:
        // io_uring's POLLRDHUP CQE fires on the IoUringIoHandler's real event
        // loop, but runTest's virtual TestCoroutineScheduler doesn't observe
        // that real-time wakeup without this.
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            val engine = NettyEngine(nettyTransport = NettyTransport.IoUring)
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val client = engine.connect("127.0.0.1", port)
            val serverCh = server.accept()

            try {
                val transport = (client as AbstractPipelinedChannel).transport
                // Public API contract: a write-only push client may keep
                // readEnabled = false for the entire connection lifetime.
                transport.readEnabled = false

                val closedSignal = CompletableDeferred<Unit>()
                transport.onReadClosed = { closedSignal.complete(Unit) }

                delay(SETTLE_MS)
                serverCh.close()

                withTimeout(EOF_DETECT_TIMEOUT_S.seconds) { closedSignal.await() }
            } finally {
                client.close()
                server.close()
                engine.close()
            }
        }
    }

    private companion object {
        private const val SETTLE_MS = 100L
        private const val EOF_DETECT_TIMEOUT_S = 5
    }
}
