package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.core.IoEngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The transport reports the end of a connection at most once, whichever
 * paths observe it — here the socket's `'end'` and the read-idle reclamation
 * that follows when nobody closes. Real loopback sockets on the Node event
 * loop; the listener is replaced so the channel does not close on the report
 * and the reclamation is reached.
 *
 * The other path the transport reports from, the socket's `'error'`, is not
 * pinned: with half-open off, Node ends the writable side with the peer's and
 * destroys the socket once both are over, and a write to a destroyed socket
 * raises no `'error'` — so `'end'` followed by `'error'` cannot be provoked on
 * loopback. That path is covered by the shared gate itself.
 */
class NodeReportInactiveOnceTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a peer FIN and then the read-idle reclamation report the end once`() = runTest(timeout = 25.seconds) {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withPeerFin(IoEngineConfig(threads = 1, idleTimeoutMillis = IDLE_MS)) { f ->
                assertEquals(1, f.reports, "the FIN is reported once")
                // Nobody closed. The read-idle timeout reclaims the connection,
                // and must not report an end that was already reported.
                assertTrue(
                    f.awaitReclaimed(IDLE_MS * RECLAIM_BUDGET_FACTOR),
                    "the idle timeout reclaimed the connection",
                )
                assertEquals(1, f.reports, "the reclamation must not report the end a second time")
            }
        }
    }

    private companion object {
        /** Short so the reclaiming case resolves well inside the test envelope. */
        private const val IDLE_MS = 300L

        /** How long past the idle timeout the reclamation may take on a loaded runner. */
        private const val RECLAIM_BUDGET_FACTOR = 6L
    }
}
