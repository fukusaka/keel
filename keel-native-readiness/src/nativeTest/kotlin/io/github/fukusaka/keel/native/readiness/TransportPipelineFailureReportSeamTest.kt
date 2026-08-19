@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.native.posix.WriteResult
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.DuplexHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.EPIPE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins what a pipeline handler hears when a refused send ends the connection:
 * the refusal on [DuplexHandler.onError] first, the end on
 * [DuplexHandler.onInactive] second, each exactly once — in both flush
 * configurations and from every entry that can meet the refusal.
 *
 * The order is the contract's, not the implementation's: `onInactive` is the
 * handler's cue to clean up, and a reason delivered after the cleanup reaches
 * nobody who can act on it. The borrowed model orders it the same way — Netty
 * fires `exceptionCaught` before `channelInactive` on every path that fires
 * both.
 *
 * A refusal met while the caller is already closing is deliberately not
 * reported: the caller asked for the connection to end and the queue to be
 * discarded, so a dead peer met while discarding is the outcome it asked for.
 */
@OptIn(ExperimentalForeignApi::class)
internal class TransportPipelineFailureReportSeamTest : TransportSeamFixture() {

    private class Recorder : DuplexHandler {
        val seen = mutableListOf<String>()
        override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
            seen += "onError(${cause::class.simpleName})"
        }

        override fun onInactive(ctx: PipelineHandlerContext) {
            seen += "onInactive"
        }
    }

    private fun refusedScenario(
        coalescing: Boolean,
        act: (AbstractPipelinedChannel, ReadinessIoTransport) -> Unit,
    ): List<String> {
        var out: List<String> = emptyList()
        runBlocking {
            withTimeout(FUNNEL_TIMEOUT_MS) {
                rebuildLoop(onLoopThread = true, runDispatchedInline = true, flushCoalescing = coalescing)
                fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
                val transport = transport()
                val ch = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}
                val rec = Recorder()
                ch.pipeline.addLast("rec", rec)
                transport.write(tracker.allocate(16).apply { writerIndex = 5 })
                runCatching { act(ch, transport) }
                runCatching { eventLoop.drainDispatched() }
                runCatching { transport.close() }
                runCatching { eventLoop.drainDispatched() }
                tracker.assertNoLeaks()
                out = rec.seen.toList()
            }
        }
        return out
    }

    private val expected = listOf("onError(RefusedWriteException)", "onInactive")

    @Test
    fun `a refused flush reports the failure and then the end on the shipping default`() {
        assertEquals(expected, refusedScenario(coalescing = true) { ch, _ -> ch.requestFlush() })
    }

    @Test
    fun `a refused flush reports the same under the coalescing opt-out`() {
        assertEquals(expected, refusedScenario(coalescing = false) { ch, _ -> ch.requestFlush() })
    }

    @Test
    fun `a refusal met by write readiness reports the same`() {
        assertEquals(expected, refusedScenario(coalescing = true) { _, t -> t.onReady(Interest.WRITE) })
    }

    @Test
    fun `a refused half-close reports the same on the shipping default`() {
        assertEquals(expected, refusedScenario(coalescing = true) { ch, _ -> ch.shutdownOutput() })
    }

    @Test
    fun `a refused half-close reports the same under the opt-out`() {
        assertEquals(expected, refusedScenario(coalescing = false) { ch, _ -> ch.shutdownOutput() })
    }

    @Test
    fun `a refusal met while the caller is closing is not reported`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The close's own teardown runs the scheduled drain and meets the
            // refusal with the connection already ending. The caller asked
            // for the queue to be discarded; a dead peer met while
            // discarding is not an error the pipeline is owed.
            rebuildLoop(onLoopThread = true, runDispatchedInline = false, flushCoalescing = true)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            val ch = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}
            val rec = Recorder()
            ch.pipeline.addLast("rec", rec)
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
            assertFalse(transport.flush(), "coalescing schedules the drain the close will run")

            transport.close()
            runCatching { eventLoop.drainDispatched() }

            assertTrue(
                rec.seen.none { it.startsWith("onError") },
                "the caller's own close is not an error to report: ${rec.seen}",
            )
            fake.assertAllConsumed()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a handler that throws in onError is still told the end`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The report runs user code before the wind-down, so a throwing
            // handler is the seam case: the report is lost for that handler,
            // the wind-down must not be.
            rebuildLoop(onLoopThread = true, runDispatchedInline = true, flushCoalescing = true)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            val ch = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}
            val seen = mutableListOf<String>()
            ch.pipeline.addLast(
                "throwing",
                object : DuplexHandler {
                    override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
                        seen += "onError"
                        throw IllegalStateException("handler failed while handling the report")
                    }

                    override fun onInactive(ctx: PipelineHandlerContext) {
                        seen += "onInactive"
                    }
                },
            )
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
            runCatching { ch.requestFlush() }
            runCatching { eventLoop.drainDispatched() }

            assertEquals(listOf("onError", "onInactive"), seen, "the wind-down survives the handler's throw")
            assertFalse(transport.isOpen, "and the connection still ends")
            fake.assertAllConsumed()
            runCatching { transport.close() }
            tracker.assertNoLeaks()
        }
    }
}
