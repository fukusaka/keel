package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Unit tests for [drainPumpThenRelease], the WebSocket teardown step that joins
 * the read pump **before** releasing the permessage-deflate decoder so native
 * zlib state is never freed under an in-flight inflate (S-D4).
 *
 * The pump and the teardown run on a multi-threaded dispatcher so the
 * ordering / deadline guarantees are exercised under genuine concurrency, not a
 * single-threaded artefact.
 */
class WebSocketPumpDrainTest {

    private class RecordingLogger : Logger {
        val warnings: MutableList<String> = mutableListOf()
        override fun isLoggable(level: LogLevel): Boolean = true
        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            if (level == LogLevel.WARN) warnings.add(message.toString())
        }
    }

    @Test
    fun `release runs only after the read pump has fully drained`() = runBlocking {
        withTimeout<Unit>(10.seconds) {
            val order = mutableListOf<String>()
            val logger = RecordingLogger()
            val started = CompletableDeferred<Unit>()
            val pump = launch(Dispatchers.Default) {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    // Models the synchronous in-flight inflate that must finish
                    // before the decoder is released.
                    withContext(NonCancellable) { order.add("pump-drained") }
                }
            }
            started.await()

            drainPumpThenRelease(pump, logger) { order.add("released") }

            assertEquals(
                listOf("pump-drained", "released"),
                order,
                "release must wait for the pump to drain (join), not race it",
            )
            assertTrue(logger.warnings.isEmpty(), "a clean drain must not warn")
        }
    }

    @Test
    fun `a pump that ignores cancellation is bounded by the deadline and released with a warning`() = runBlocking {
        withTimeout<Unit>(10.seconds) {
            val logger = RecordingLogger()
            val started = CompletableDeferred<Unit>()
            val unblock = CompletableDeferred<Unit>()
            // A pump wedged in a non-cancellable wait — cancelAndJoin can never
            // complete; only the test releases it after the assertions.
            val pump = launch(Dispatchers.Default) {
                withContext(NonCancellable) {
                    started.complete(Unit)
                    unblock.await()
                }
            }
            started.await()

            var released = false
            val elapsed = measureTime {
                drainPumpThenRelease(pump, logger, timeoutMillis = DEADLINE_MS) { released = true }
            }

            assertTrue(released, "release must still run after the drain deadline elapses")
            assertEquals(1, logger.warnings.size, "an undrained pump must warn exactly once")
            assertTrue(elapsed < 5.seconds, "teardown must be bounded by the deadline, not hang")

            unblock.complete(Unit) // let the wedged pump finish so runBlocking can complete
        }
    }

    private companion object {
        const val DEADLINE_MS = 100L
    }
}
