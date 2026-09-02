@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.native.posix.ReadResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * That this transport announces the end of the reads it had for one event.
 *
 * The signal has an entrance on `Pipeline` and a callback on
 * `PipelineHandler`, and until it was wired no transport could send it. What
 * a handler does with it is answer a burst with one flush rather than one per
 * message — one read can carry several pipelined requests, so the boundary is
 * coarser than the messages a decoder makes out of it, which is the whole
 * point.
 *
 * These drive the loop-facing entrance, so they fail if the announcement is
 * removed from the read path. The pipeline's side of the same wiring is
 * `ReadBatchBoundaryTest` in `keel-core`; neither covers the other, because
 * the transport can be silent while the channel is wired correctly and the
 * other way round.
 */
@OptIn(ExperimentalForeignApi::class)
internal class TransportReadBatchBoundarySeamTest : TransportSeamFixture() {

    @Test
    fun `a read event ends with the boundary that closes it`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            fake.enqueueRead(fd, ReadResult.Bytes(4))
            val transport = transport()
            val events = mutableListOf<String>()
            transport.onRead = {
                events.add("read")
                it.release()
            }
            transport.onReadComplete = { events.add("batchEnd") }
            transport.readEnabled = true

            transport.onReady(Interest.READ)

            assertEquals(
                listOf("read", "batchEnd"),
                events,
                "the reads this event had, then the word that there are no more of them for now",
            )
            fake.assertAllConsumed()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `two read events are two boundaries`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            fake.enqueueRead(fd, ReadResult.Bytes(4))
            fake.enqueueRead(fd, ReadResult.Bytes(4))
            val transport = transport()
            val events = mutableListOf<String>()
            transport.onRead = {
                events.add("read")
                it.release()
            }
            transport.onReadComplete = { events.add("batchEnd") }
            transport.readEnabled = true

            transport.onReady(Interest.READ)
            transport.onReady(Interest.READ)

            // This engine reads once per event and re-arms, so a boundary per
            // event is the finest grouping it can state truthfully. It does not
            // coalesce two events into one boundary, because it cannot know
            // that a second event is coming.
            assertEquals(listOf("read", "batchEnd", "read", "batchEnd"), events)
            fake.assertAllConsumed()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `an event that ends the connection announces no boundary`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            fake.enqueueRead(fd, ReadResult.Eof)
            val transport = transport()
            val events = mutableListOf<String>()
            transport.onRead = {
                events.add("read")
                it.release()
            }
            transport.onReadComplete = { events.add("batchEnd") }
            transport.readEnabled = true

            transport.onReady(Interest.READ)

            // The peer's FIN is not a batch of reads that finished; it is the
            // read side ending. A handler told otherwise would flush into a
            // connection that has stopped receiving.
            assertEquals(emptyList<String>(), events)
            fake.assertAllConsumed()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `an event with nothing to read announces no boundary`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            fake.enqueueRead(fd, ReadResult.WouldBlock)
            val transport = transport()
            val events = mutableListOf<String>()
            transport.onRead = {
                events.add("read")
                it.release()
            }
            transport.onReadComplete = { events.add("batchEnd") }
            transport.readEnabled = true

            transport.onReady(Interest.READ)

            // A spurious wake. Netty cannot make this distinction and reports a
            // boundary anyway, which is why the callback's contract permits one
            // with nothing before it; this engine can, so it stays quiet.
            assertEquals(emptyList<String>(), events)
            fake.assertAllConsumed()
            tracker.assertNoLeaks()
        }
    }
}
