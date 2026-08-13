@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.StreamServer
import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import io.github.fukusaka.keel.native.readiness.PosixIoTransport
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.EBADF
import platform.posix.close
import platform.posix.dup
import platform.posix.errno
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end coverage for closing a Coroutine-mode channel *after* the engine
 * has stopped: the channel's loop can no longer run the teardown, so `close()`
 * has to release the socket on the caller instead.
 *
 * This is the counterpart of the loop-stop notification tests: those pin that
 * a stopping loop *tells* every live connection; this pins that a told
 * connection can still *let go* of its descriptor. Before the caller-thread
 * fallback, the teardown was dispatched onto the stopped loop's queue and the
 * fd stayed open until process exit.
 */
class KqueueCloseAfterLoopStopTest {

    @Test
    fun `a channel closed after the engine stopped releases its fd`() = runBlocking {
        withTimeout(BODY_TIMEOUT_S.seconds) {
            val engine = KqueueEngine()
            var client: Channel? = null
            var serverCh: Channel? = null
            var server: StreamServer? = null
            try {
                server = engine.bind(LOOPBACK_HOST, 0)
                val port = (server.localAddress as InetSocketAddress).port
                client = engine.connect(LOOPBACK_HOST, port)
                serverCh = server.accept()
                val clientFd = ((client as AbstractPipelinedChannel).transport as PosixIoTransport).fd
                val serverChFd = ((serverCh as AbstractPipelinedChannel).transport as PosixIoTransport).fd

                engine.close()

                // Premise, not the assertion under test: the engine tells a
                // Coroutine-mode connection its loop stopped but deliberately
                // does not close it — the caller owns the resource.
                val premise = dup(clientFd)
                if (premise >= 0) close(premise)
                assertTrue(premise >= 0, "premise: the fd is still open right after engine.close()")

                client.close()
                serverCh.close()
                server.close()

                val clientProbe = dup(clientFd)
                if (clientProbe >= 0) close(clientProbe)
                assertEquals(-1, clientProbe, "close() on a stopped loop must still release the client fd")
                assertEquals(EBADF, errno, "closed, not fd-table exhaustion: the probe must fail with EBADF")
                val serverProbe = dup(serverChFd)
                if (serverProbe >= 0) close(serverProbe)
                assertEquals(-1, serverProbe, "close() on a stopped loop must still release the accepted fd")
                assertEquals(EBADF, errno, "closed, not fd-table exhaustion: the probe must fail with EBADF")
            } finally {
                // Idempotent on the happy path; on a failed assert mid-body it
                // still releases the channels and stops the loop threads so
                // they do not outlive this test in the shared process.
                client?.close()
                serverCh?.close()
                server?.close()
                engine.close()
            }
        }
    }

    private companion object {
        /** Whole-test budget; generous because engine start/stop joins threads. */
        const val BODY_TIMEOUT_S = 15
    }
}
