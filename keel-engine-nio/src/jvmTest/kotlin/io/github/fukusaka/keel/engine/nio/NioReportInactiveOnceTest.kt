package io.github.fukusaka.keel.engine.nio

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
class NioReportInactiveOnceTest {
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
    fun `reads re-armed after a peer FIN read the same end again and report nothing`() = runTest {
        withPeerFin(IoEngineConfig(threads = 1)) { f ->
            assertEquals(1, f.reports, "the FIN is reported once")
            // The caller disables and re-enables reads: the re-armed read
            // observes the same end of file, which is not news.
            f.onLoop {
                f.transport.readEnabled = false
                f.transport.readEnabled = true
            }
            delay(SETTLE_MS)
            assertEquals(1, f.reports, "a read re-armed after the FIN must not report it again")
            assertTrue(f.transport.isOpen, "a FIN alone does not end the connection")
        }
    }

    private companion object {
        /** Short so the reclaiming case resolves well inside [TEST_TIMEOUT]. */
        private const val IDLE_MS = 300L

        /** How long past the idle timeout the reclamation may take on a loaded runner. */
        private const val RECLAIM_BUDGET_FACTOR = 6L

        /** Long enough for a re-armed loopback read to have completed. */
        private const val SETTLE_MS = 200L
    }
}
