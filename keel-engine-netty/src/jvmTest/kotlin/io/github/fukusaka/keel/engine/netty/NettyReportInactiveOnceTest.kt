package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.core.IoEngineConfig
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The transport reports the end of a connection at most once, whichever
 * paths observe it. Real loopback sockets; the listener is replaced so the
 * channel does not close on the report and the later paths are reached.
 */
class NettyReportInactiveOnceTest {
    @Test
    fun `a peer FIN and then the read-idle reclamation report the end once`() = runTest {
        withPeerFin(IoEngineConfig(threads = 1, idleTimeoutMillis = IDLE_MS)) { f ->
            assertEquals(1, f.reports, "the FIN is reported once")
            // Nobody closed. The read-idle timeout reclaims the connection, and
            // must not report an end that was already reported.
            assertTrue(f.awaitReclaimed(IDLE_MS * RECLAIM_BUDGET_FACTOR), "the idle timeout reclaimed the connection")
            assertEquals(1, f.reports, "the reclamation must not report the end a second time")
        }
    }

    @Test
    fun `a peer FIN on a client channel is reported once from channelInactive and re-enabling reads adds nothing`() = runTest {
        // The engine allows half-closure on its server channels only, so on
        // the client channel Netty closes on the FIN and the end arrives as
        // `channelInactive`. Re-enabling reads afterwards arms nothing (the
        // Netty channel is closed) — pinned so the test says what it tests.
        withPeerFin(IoEngineConfig(threads = 1)) { f ->
            assertEquals(1, f.reports, "the FIN is reported once")
            f.onLoop {
                f.transport.readEnabled = false
                f.transport.readEnabled = true
            }
            delay(SETTLE_MS)
            assertEquals(1, f.reports, "re-enabling reads after the FIN must not report it again")
        }
    }

    private companion object {
        /** Short so the reclaiming case resolves well inside [TEST_TIMEOUT]. */
        private const val IDLE_MS = 300L

        /** How long past the idle timeout the reclamation may take on a loaded runner. */
        private const val RECLAIM_BUDGET_FACTOR = 6L

        /** Long enough for anything the re-enable could have started to have completed. */
        private const val SETTLE_MS = 200L
    }
}
