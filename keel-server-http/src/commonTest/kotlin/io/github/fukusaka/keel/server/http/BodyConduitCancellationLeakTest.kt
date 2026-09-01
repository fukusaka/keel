package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpRequestHead
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Regression: [Http1Call.onBodyChunk]'s direct hand-off resumes a suspended
 * body consumer with a pooled `IoBuf`. kotlinx-coroutines' prompt-cancellation
 * guarantee means a consumer cancelled after the resume is dispatched but
 * before the continuation runs never receives the value — so the pooled buffer
 * must be released by the resume's `onCancellation` handler, or it leaks.
 *
 * The hand-off is shared by `receiveChunk` / `receiveBytes` / `receiveChunks`
 * (they all pull from the same `bodyWaiter`), so fixing it covers all three.
 *
 * A [StandardTestDispatcher] makes the resume-then-cancel window deterministic:
 * a real dispatcher (and the [HttpServerHandlerFixture] harness's
 * `Dispatchers.Unconfined`) resumes the continuation inline, leaving no window
 * to cancel inside. Here the resumed continuation is queued, cancelled, then
 * run — reproducing the leak exactly.
 */
class BodyConduitCancellationLeakTest {

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `direct hand-off releases the pooled buffer when the consumer is cancelled in the resume window`() =
        runTest(timeout = 15.seconds) {
            val tracker = TrackingAllocator(DefaultAllocator)
            val call = Http1Call(
                HttpRequestHead(HttpMethod.POST, "/upload"),
                ThrowingHandlerContext,
                QueryParameters.EMPTY,
                emptyMap(),
                resolution = RouteResolution.Unmatched,
                isUpgrade = false,
            )
            val content = tracker.allocate(4).apply {
                writeByteArray(byteArrayOf(1, 2, 3, 4), 0, 4)
            }

            // A consumer suspends on receiveChunk — `bodyWaiter` is now set.
            val consumer = launch(StandardTestDispatcher(testScheduler)) {
                call.receiveChunk()?.release()
            }
            runCurrent()

            // Hand the chunk to the waiter: the resume dispatches (queues) the
            // continuation, which has not run yet.
            call.onBodyChunk(content, last = false)

            // Cancel the consumer inside the resume window.
            consumer.cancel()

            // Run the queued continuation: the cancelled job triggers prompt
            // cancellation, discarding `content`. The resume's onCancellation
            // handler must release it.
            advanceUntilIdle()

            assertEquals(
                0,
                tracker.outstandingCount,
                "a pooled IoBuf handed to a consumer cancelled in the resume window must be released, not leaked",
            )
        }
}

/**
 * A [PipelineHandlerContext] that throws on every member. The body-conduit
 * direct hand-off path exercised above never touches the context — it only
 * sets/clears `bodyWaiter` and resumes — so any access here is a test bug.
 */
private object ThrowingHandlerContext : PipelineHandlerContext {
    private fun unused(): Nothing =
        error("ThrowingHandlerContext: the direct hand-off path must not touch the context")

    override val channel get() = unused()
    override val pipeline get() = unused()
    override val name get() = unused()
    override val handler get() = unused()
    override val allocator get() = unused()
    override fun propagateActive() = unused()
    override fun propagateRead(msg: Any) = unused()
    override fun propagateReadComplete() = unused()

    override fun propagateFlushComplete() = unused()
    override fun propagateInactive() = unused()
    override fun propagateError(cause: Throwable) = unused()
    override fun propagateUserEvent(event: Any) = unused()
    override fun propagateWritabilityChanged(isWritable: Boolean) = unused()
    override fun propagateWrite(msg: Any) = unused()
    override fun propagateFlush() = unused()
    override fun propagateClose() = unused()
}
