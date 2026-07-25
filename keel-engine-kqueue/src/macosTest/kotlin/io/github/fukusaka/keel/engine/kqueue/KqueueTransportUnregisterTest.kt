package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Closing a connection must withdraw its callback registration.
 *
 * The registrations map is keyed by fd number, so one left behind is not a
 * growing leak — the next connection on that number overwrites it — but until
 * then it keeps the transport, its channel and the pipeline graph behind it
 * reachable. The server side already withdraws its listener on close; the
 * transport did not.
 */
class KqueueTransportUnregisterTest {

    @Test
    fun `closing a connection withdraws its callback registration`() = runBlocking {
        withTimeout(TEST_BUDGET_S.seconds) {
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val before = engine.workerRegistrationCount()

            val client = engine.connect("127.0.0.1", port)
            val accepted = server.accept()
            client.close()
            accepted.close()

            // Settle: close is asynchronous by contract, so the teardown runs
            // on the loop some time after close() returns.
            withTimeout(SETTLE_BUDGET_S.seconds) {
                while (engine.workerRegistrationCount() > before) {
                    kotlinx.coroutines.delay(POLL_MS)
                }
            }
            assertEquals(
                before,
                engine.workerRegistrationCount(),
                "a closed connection left its callback registration behind; it keeps the " +
                    "transport and everything it references reachable until the fd number is reused.",
            )

            server.close()
            engine.close()
        }
    }

    private companion object {
        private const val TEST_BUDGET_S = 20
        private const val SETTLE_BUDGET_S = 5
        private const val POLL_MS = 10L
    }
}
