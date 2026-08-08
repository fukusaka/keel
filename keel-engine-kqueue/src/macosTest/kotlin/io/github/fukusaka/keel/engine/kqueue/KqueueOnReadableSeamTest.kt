package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.FdReadyListener
import io.github.fukusaka.keel.native.posix.Interest
import io.github.fukusaka.keel.native.posix.ReadResult
import io.github.fukusaka.keel.native.posix.WriteResult
import io.github.fukusaka.keel.testing.buf.FailingReleaseIoBuf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.ECONNRESET
import platform.posix.F_GETFD
import platform.posix.close
import platform.posix.fcntl
import platform.posix.pipe
import platform.posix.write
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Seam-level unit tests for [KqueueIoTransport.onReadable] — macOS
 * counterpart of `EpollOnReadableSeamTest`, same 4-case coverage of
 * the [ReadResult] branch space via scripted [FakeNativeSocket]
 * responses. Direct regression coverage for the PR #321
 * `EINTR → onReadClosed` misclassification.
 *
 * Part of the project's two-layer seam + integration testing strategy
 * (this file covers the seam side).
 */
@OptIn(ExperimentalForeignApi::class)
class KqueueOnReadableSeamTest {

    /**
     * An allocator that cannot serve a read buffer.
     *
     * Stands in for this connection's own plumbing failing on the loop thread:
     * a user handler's throw is contained by the pipeline and a resumed
     * coroutine's by the loop's per-task guard, so what actually reaches the
     * readiness frame is something like a native heap that will not give up a
     * buffer.
     */
    private object FailingAllocator : BufferAllocator {
        override fun allocate(capacity: Int): IoBuf = throw OutOfMemoryError("no buffer for you")
        override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? = null
        override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
            throw UnsupportedOperationException("this allocator exists to fail allocate")
    }

    /**
     * An `onReadClosed` that fails the way the production one can: once.
     *
     * The route this callback takes is `pipeline.notifyInactive()`, which sets
     * `inactiveObserved` *before* it dispatches the chain, and then `close()`,
     * whose `markClosing()` flips `opened` exactly once. A chain that threw is
     * therefore short-circuited on re-entry, so whatever failed the first time
     * returns normally the second.
     *
     * A stub that throws on every call cannot show that, and it is the
     * difference that matters here: it lets a fallback which decides "did the
     * teardown finish?" by calling this again read as protective when against a
     * real callback it never fires.
     */
    private fun failsOnceLikeProduction(message: String, calls: AtomicInt = AtomicInt(0)): () -> Unit =
        { if (calls.incrementAndGet() == 1) throw IllegalStateException(message) }

    /**
     * Runs [block] on the EventLoop thread and returns what it threw, if
     * anything.
     *
     * Readiness runs on the loop in production, and that is not a detail here:
     * `close()` hands off to [KqueueEventLoop.runOnLoop], which runs the teardown
     * inline on the loop thread and dispatches it from anywhere else. Driving
     * these paths from the test thread therefore moves every teardown failure
     * out of the frame under test, where nothing can observe it.
     *
     * The throwable is captured inside the task rather than let out of it: the
     * loop's own per-task guard would otherwise swallow it, and a test that
     * asserted on the absence of a throw would pass against any build.
     */
    private fun onLoopCatching(block: () -> Unit): Throwable? {
        val outcome = CompletableDeferred<Result<Unit>>()
        eventLoop.dispatch(EmptyCoroutineContext, Runnable { outcome.complete(runCatching(block)) })
        return runBlocking { withTimeout(10.seconds) { outcome.await() } }.exceptionOrNull()
    }

    private val logger = NoopLoggerFactory.logger("KqueueOnReadableSeamTest")
    private lateinit var eventLoop: KqueueEventLoop
    private var readFd: Int = -1
    private var writeFd: Int = -1

    @BeforeTest
    fun setUp() {
        eventLoop = KqueueEventLoop(logger)
        eventLoop.start()
        val pair = createPipe()
        readFd = pair.first
        writeFd = pair.second
    }

    @AfterTest
    fun tearDown() {
        close(writeFd)
        // Only if this fixture still owns it. A test that lets the transport
        // close the connection hands `readFd` over: the transport's teardown is
        // dispatched to the loop, so closing here as well is a second close(2)
        // on the same number from another thread -- and if anything on the loop
        // or in `eventLoop.close()` opens a descriptor in between, the loser
        // closes a live one belonging to something else.
        if (readFd >= 0) close(readFd)
        eventLoop.close()
    }

    /**
     * Marks `readFd` as belonging to the transport from here on, so [tearDown]
     * does not close it a second time.
     */
    private fun surrenderReadFd() {
        readFd = -1
    }

    private fun createPipe(): Pair<Int, Int> {
        val fds = IntArray(2)
        val ok = fds.usePinned { pinned ->
            pipe(pinned.addressOf(0)) == 0
        }
        check(ok) { "pipe() failed" }
        return fds[0] to fds[1]
    }

    private fun triggerReadiness() {
        val buf = byteArrayOf(0x78)
        buf.usePinned { pinned ->
            write(writeFd, pinned.addressOf(0), 1uL)
        }
    }

    @Test
    fun `onReadable with Bytes invokes onRead and re-arms`() = runBlocking {
        val fake = FakeNativeSocket().apply {
            enqueueRead(readFd, ReadResult.Bytes(3), ReadResult.WouldBlock)
        }
        val transport = KqueueIoTransport(readFd, eventLoop, DefaultAllocator, fake)

        val firstRead = CompletableDeferred<Int>()
        transport.onRead = { buf ->
            if (!firstRead.isCompleted) firstRead.complete(buf.readableBytes)
            buf.release()
        }
        transport.readEnabled = true
        triggerReadiness()

        val bytes = withTimeout(2.seconds) { firstRead.await() }
        assertEquals(3, bytes)
        assertTrue(fake.readCalls >= 1)
    }

    @Test
    fun `onReadable with Eof invokes onReadClosed exactly once`() = runBlocking {
        val fake = FakeNativeSocket().apply {
            enqueueRead(readFd, ReadResult.Eof)
        }
        val transport = KqueueIoTransport(readFd, eventLoop, DefaultAllocator, fake)

        val closedSignal = CompletableDeferred<Unit>()
        var readFired = 0
        transport.onRead = { buf ->
            readFired++
            buf.release()
        }
        transport.onReadClosed = { closedSignal.complete(Unit) }
        transport.readEnabled = true
        triggerReadiness()

        withTimeout(2.seconds) { closedSignal.await() }
        assertEquals(1, fake.readCalls)
        assertEquals(0, readFired, "Eof must not deliver a buffer")
    }

    @Test
    fun `onReadable with WouldBlock releases buffer and re-arms`() = runBlocking {
        val fake = FakeNativeSocket().apply {
            enqueueRead(readFd, ReadResult.WouldBlock, ReadResult.Eof)
        }
        val transport = KqueueIoTransport(readFd, eventLoop, DefaultAllocator, fake)

        val closedSignal = CompletableDeferred<Unit>()
        var readFired = 0
        transport.onRead = { buf ->
            readFired++
            buf.release()
        }
        transport.onReadClosed = { closedSignal.complete(Unit) }
        transport.readEnabled = true
        triggerReadiness()

        withTimeout(2.seconds) { closedSignal.await() }
        assertEquals(0, readFired, "WouldBlock must not deliver a buffer")
        assertTrue(fake.readCalls >= 2, "WouldBlock must re-arm — next read was Eof")
    }

    @Test
    fun `onReadable with Failed invokes onReadClosed`() = runBlocking {
        val fake = FakeNativeSocket().apply {
            enqueueRead(readFd, ReadResult.Failed(platform.posix.ECONNRESET))
        }
        val transport = KqueueIoTransport(readFd, eventLoop, DefaultAllocator, fake)

        val closedSignal = CompletableDeferred<Unit>()
        var readFired = 0
        transport.onRead = { buf ->
            readFired++
            buf.release()
        }
        transport.onReadClosed = { closedSignal.complete(Unit) }
        transport.readEnabled = true
        triggerReadiness()

        withTimeout(2.seconds) { closedSignal.await() }
        assertEquals(1, fake.readCalls)
        assertEquals(0, readFired, "Failed must not deliver a buffer")
    }

    @Test
    fun `readiness handling that throws closes the connection instead of the loop`() = runBlocking {
        withTimeout(15.seconds) {
            // Before this was guarded the throw left onReady, the readiness
            // dispatch and the loop body, and reached a pthread entry point with
            // nothing above it to catch -- ending the process, and with it every
            // other connection on this engine, over one socket's buffer.
            val fake = FakeNativeSocket()
            val transport = KqueueIoTransport(readFd, eventLoop, FailingAllocator, fake)
            surrenderReadFd()
            transport.onChannelAttached()
            transport.readEnabled = true

            assertEquals(
                null,
                onLoopCatching { transport.onReady(Interest.READ) },
                "the wind-down completed, so nothing reaches the loop",
            )
            assertFalse(
                transport.isOpen,
                "the connection whose readiness could not be handled is the unit that dies",
            )
        }
    }

    @Test
    fun `a read that throws releases the buffer it was holding`() = runBlocking {
        withTimeout(15.seconds) {
            // Nothing but this frame can see that buffer between the allocation
            // and the hand-off: `close()` releases `pendingWrites`, which it can
            // reach, and a local is not that. Before the body owned what it
            // allocated, a throw in the window lost a pooled buffer per failed
            // read -- quietly, since the connection dies either way.
            val tracker = TrackingAllocator(DefaultAllocator)
            val fake = FakeNativeSocket().apply {
                readThrowsOnce = IllegalStateException("the read path failed mid-flight")
            }
            val transport = KqueueIoTransport(readFd, eventLoop, tracker, fake)
            surrenderReadFd()
            transport.onChannelAttached()
            transport.readEnabled = true

            assertEquals(
                null,
                onLoopCatching { transport.onReady(Interest.READ) },
                "the wind-down completed, so nothing reaches the loop",
            )
            assertFalse(transport.isOpen, "the connection is still the unit that dies")
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a peer close that throws ends the connection and reaches the loop`() = runBlocking {
        withTimeout(15.seconds) {
            // The other half of what the readiness dispatch calls into. Guarding
            // only `onReady` left this one falling through to the backstop in the
            // event loop, which releases nothing on the listener's behalf: the
            // loop survived and the connection sat in CLOSE-WAIT holding its
            // descriptor, with nobody left who would close it.
            val fake = FakeNativeSocket()
            val transport = KqueueIoTransport(readFd, eventLoop, DefaultAllocator, fake)
            transport.onChannelAttached()
            val notifyCalls = AtomicInt(0)
            transport.onReadClosed = failsOnceLikeProduction("the close handler failed", notifyCalls)
            surrenderReadFd()
            transport.readEnabled = true

            // Raised, not swallowed: on this path the notification the guard
            // falls back to is the very call that failed, and in Pipeline mode
            // that call is what closes the connection. A throw out of it is a
            // teardown that did not finish, and the loop's backstop is what
            // drops the registration and takes the interest back.
            val thrown = onLoopCatching { transport.onPeerClosed(Interest.READ) }

            assertTrue(thrown is IllegalStateException, "expected the handler's failure, got $thrown")
            assertEquals("the close handler failed", thrown.message)
            assertFalse(
                transport.isOpen,
                "a peer close that cannot be delivered still ends this connection",
            )
            // And the guard does not ask again. Calling back into a callback
            // that just failed is how this path came to look protected: the
            // second call returns normally -- the pipeline short-circuits on
            // `inactiveObserved` -- and reading that return as "the teardown
            // finished" is what swallowed the failure.
            assertEquals(1, notifyCalls.value, "the failed notification is not retried for an answer")
        }
    }

    @Test
    fun `a close that throws while ending the connection reaches the loop`() = runBlocking {
        withTimeout(15.seconds) {
            // The notification succeeds and the close is what fails. That is the
            // ordinary shape in Coroutine mode, where `onReadClosed` reports and
            // does not close, so the close here is the entire teardown -- and a
            // teardown that throws part-way is not retried: the claim is spent,
            // so the fd is never closed, the ledger entries are never withdrawn,
            // this transport stays in the participant registry, and a caller
            // parked in `awaitPendingFlush` is never woken. An earlier revision
            // caught this throw and re-raised only when the *notification* had
            // failed, which put back one call along the hole it had just fixed.
            // A write that succeeds, so that a flush which should not happen
            // would reach a release rather than stalling on `WouldBlock` --
            // otherwise the guard below is asserted against a path that could
            // not have refused anything either way.
            val fake = FakeNativeSocket().apply { defaultWrite = WriteResult.Written(4) }
            val transport = KqueueIoTransport(readFd, eventLoop, FailingAllocator, fake)
            // The teardown under test aborts before it closes this, so the
            // fixture has to. Taken before surrendering, because that is what
            // stops `tearDown` from closing a descriptor the transport owns.
            val abandonedFd = readFd
            surrenderReadFd()
            transport.onChannelAttached()
            var reportedInactive = 0
            transport.onReadClosed = { reportedInactive++ }
            transport.readEnabled = true

            // Queued, not flushed: the teardown's release of the pending writes
            // is what throws. The second one is what the throw abandons -- the
            // drain stops where it failed -- and it is how the write half is
            // checked below, because a queue with nothing left in it cannot
            // show whether anything walked back into it.
            val queued = FailingReleaseIoBuf(DefaultAllocator.allocate(16).apply { writerIndex = 4 })
            val abandoned = FailingReleaseIoBuf(DefaultAllocator.allocate(16).apply { writerIndex = 4 })
            transport.write(queued)
            transport.write(abandoned)

            val thrown = onLoopCatching { transport.onReady(Interest.READ) }

            assertTrue(thrown is OutOfMemoryError, "the readiness failure is what is raised, got $thrown")
            assertEquals(
                listOf<String?>("release refused by FailingReleaseIoBuf"),
                thrown.suppressedExceptions.map { it.message },
                "the failure that ended the wind-down travels with the one that started it",
            )
            assertEquals(1, reportedInactive, "the notification itself succeeded")
            assertEquals(1, queued.refusedReleases)

            // The same readiness on the write half must not walk back into what
            // the aborted teardown left: `abandoned` is still queued. In
            // production the WRITE registration outlives the abort too, because
            // the backstop takes back only the interest that fired; this
            // fixture never arms one, so what is checked here is the transport's
            // own refusal to act, not the arrival. An `EV_ADD` filter is persistent, so write
            // readiness keeps arriving for as long as the fd is open, which is
            // now forever.
            //
            // The `opened` guard in `onWritable` is the only thing stopping it,
            // and this is what pins it: these buffers hand out a native pointer,
            // so without the guard the flush reaches `abandoned`'s release
            // rather than failing on a cast, and refuses -- which comes back out
            // of the readiness call.
            val onWrite = onLoopCatching { transport.onReady(Interest.WRITE) }

            assertEquals(null, onWrite, "write readiness on an ended connection does nothing")
            // Directly, not through what a flush would have done with the
            // buffers: an assertion about the refusal only bites while the
            // scripted write succeeds, and one about the syscall bites whatever
            // the fake is set to answer.
            assertEquals(0, fake.writeCalls + fake.writevCalls, "no flush was attempted")
            assertEquals(0, abandoned.refusedReleases, "nothing walked back into what the abort left")

            assertTrue(queued.releaseUnderlying(), "the fixture cleans up what the teardown could not")
            assertTrue(abandoned.releaseUnderlying(), "and the one the abort left queued")
            // 0 rather than EBADF: the aborted teardown really did leave this
            // open, which is the cost the test is named for.
            assertEquals(0, close(abandonedFd), "the fixture closes what the teardown could not")
        }
    }

    @Test
    fun `a failed read whose notification throws ends the connection and reaches the loop`() = runBlocking {
        withTimeout(15.seconds) {
            // The third path on which the guarded body is itself the
            // notification. It differs from the EOF one only in which branch
            // gets there, which is exactly why it is easy to leave behind: a
            // revert of this one arm alone passes every other test.
            val tracker = TrackingAllocator(DefaultAllocator)
            val fake = FakeNativeSocket().apply { enqueueRead(readFd, ReadResult.Failed(ECONNRESET)) }
            val transport = KqueueIoTransport(readFd, eventLoop, tracker, fake)
            transport.onChannelAttached()
            val notifyCalls = AtomicInt(0)
            transport.onReadClosed = failsOnceLikeProduction("the failed-read handler failed", notifyCalls)
            surrenderReadFd()
            transport.readEnabled = true

            val thrown = onLoopCatching { transport.onReady(Interest.READ) }

            assertTrue(thrown is IllegalStateException, "expected the handler's failure, got $thrown")
            assertEquals("the failed-read handler failed", thrown.message)
            assertEquals(1, notifyCalls.value, "the failed notification is not retried for an answer")
            assertFalse(transport.isOpen, "the connection is still ended")
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a release that throws mid-flush does not leave the buffers before it queued`() = runBlocking {
        withTimeout(15.seconds) {
            // The expensive shape. The flush releases what it wrote, and a
            // refusal part-way used to leave everything already released still
            // in the queue -- so the teardown that follows released them a
            // second time, failed the reference-count check at its first step,
            // and abandoned the fd, the ledger entries, the registry slot and
            // the flush waiter. One refused release, a whole connection.
            val tracker = TrackingAllocator(DefaultAllocator)
            val fake = FakeNativeSocket().apply { enqueueWritev(readFd, WriteResult.Written(8)) }
            val transport = KqueueIoTransport(readFd, eventLoop, tracker, fake)
            val abandonedFd = readFd
            surrenderReadFd()
            transport.onChannelAttached()
            var reportedInactive = 0
            transport.onReadClosed = { reportedInactive++ }

            // Two, and the refusal second: the buffer released before it is the
            // one that used to be released again.
            transport.write(tracker.allocate(16).apply { writerIndex = 4 })
            val refusing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 4 })
            transport.write(refusing)

            val thrown = onLoopCatching { transport.onReady(Interest.WRITE) }

            assertEquals(
                null,
                thrown,
                "the wind-down completed, so nothing reaches the loop",
            )
            assertEquals(1, refusing.refusedReleases)
            assertEquals(1, reportedInactive, "the connection still ended the ordinary way")
            assertFalse(transport.isOpen)
            // Asked, not closed. The teardown owns this descriptor and closed
            // it, so the number is free from that moment -- and `tearDown` in
            // this class refuses to close a surrendered fd for exactly that
            // reason. `fcntl` answers the same question without touching
            // whatever may have taken the number: EBADF means the teardown ran
            // all the way through, which it could not do while the drain left
            // it a double release.
            assertEquals(
                -1,
                fcntl(abandonedFd, F_GETFD),
                "the teardown closed the descriptor itself",
            )

            assertTrue(refusing.releaseUnderlying(), "the fixture cleans up the one that refused")
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `an EOF whose notification throws ends the connection and reaches the loop`() = runBlocking {
        withTimeout(15.seconds) {
            // The same shape as the peer-close path, reached through the read
            // instead: the guarded body is what notifies, so the fallback's own
            // call is the second one. The connection this describes is an
            // ordinary FIN -- the close arrives as a `read()` returning 0 --
            // which makes it the common way in, not a corner.
            val tracker = TrackingAllocator(DefaultAllocator)
            val fake = FakeNativeSocket().apply { enqueueRead(readFd, ReadResult.Eof) }
            val transport = KqueueIoTransport(readFd, eventLoop, tracker, fake)
            transport.onChannelAttached()
            val notifyCalls = AtomicInt(0)
            transport.onReadClosed = failsOnceLikeProduction("the EOF handler failed", notifyCalls)
            surrenderReadFd()
            transport.readEnabled = true

            val thrown = onLoopCatching { transport.onReady(Interest.READ) }

            assertTrue(thrown is IllegalStateException, "expected the handler's failure, got $thrown")
            assertEquals("the EOF handler failed", thrown.message)
            assertEquals(1, notifyCalls.value, "the failed notification is not retried for an answer")
            assertFalse(transport.isOpen, "the connection is still ended")
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a listener that re-arms and then throws is disarmed rather than spun`() = runBlocking {
        withTimeout(15.seconds) {
            // Popping the ledger entry before the call is not by itself
            // protection. A listener that arms before doing its work has put a
            // fresh entry back by the time it throws, and the interest is still
            // ready -- nothing consumed the byte -- so the next turn finds the
            // entry and calls straight back into the same throw. One ERROR a
            // turn, for as long as the fd is open.
            val calls = AtomicInt(0)
            val listener = object : FdReadyListener {
                override fun onReady(interest: Interest) {
                    calls.incrementAndGet()
                    eventLoop.registerCallback(readFd, Interest.READ, this)
                    throw IllegalStateException("armed, then failed")
                }
            }
            eventLoop.registerCallback(readFd, Interest.READ, listener)

            triggerReadiness() // and nothing ever reads the byte back out

            // Await the first call rather than sleeping for it: a fixed wait
            // that is too short reports 0, which fails this test for the one
            // reason it is not about. The sleep after it is for the second
            // call, which must not come -- unbounded waiting cannot show that.
            withTimeout(10.seconds) {
                while (calls.value == 0) delay(10)
            }
            delay(300)
            assertEquals(
                1,
                calls.value,
                "the interest must not be handed back to the listener that just threw",
            )
        }
    }

    @Test
    fun `readiness that throws reports the connection inactive as well as closing it`() = runBlocking {
        withTimeout(15.seconds) {
            // `close()` releases what it can reach -- the pending writes, the
            // registrations, the fd -- and tells nobody. `onReadClosed` is the
            // only route to `pipeline.notifyInactive()`, and that is what runs
            // each handler's `onInactive`: held body chunks, a borrowed header
            // set, the server's connection-registry entry, and the EOF that
            // wakes a caller parked in a Coroutine-mode `read()`. An earlier
            // revision closed without it, leaking the first three per failed
            // connection and hanging the fourth for good.
            val fake = FakeNativeSocket()
            val transport = KqueueIoTransport(readFd, eventLoop, FailingAllocator, fake)
            surrenderReadFd()
            var reportedInactive = 0
            transport.onChannelAttached()
            transport.onReadClosed = { reportedInactive++ }
            transport.readEnabled = true

            assertEquals(
                null,
                onLoopCatching { transport.onReady(Interest.READ) },
                "the wind-down completed, so nothing reaches the loop",
            )
            assertEquals(
                1,
                reportedInactive,
                "the pipeline learns the connection ended, the way an idle timeout reports it",
            )
            assertFalse(transport.isOpen)
        }
    }

    @Test
    fun `a failure while ending the connection reaches the loop rather than being swallowed`() = runBlocking {
        withTimeout(15.seconds) {
            // The guard's fallback runs `onReadClosed`, which in Pipeline mode
            // is what performs the teardown. Swallowing a throw from there left
            // the descriptor open with its registration intact -- and, because
            // the loop then saw a listener that had not failed, its interest
            // still armed, re-entering the same failure every turn. The
            // teardown claim is spent by then, so no later close() retries it.
            val fake = FakeNativeSocket()
            val transport = KqueueIoTransport(readFd, eventLoop, FailingAllocator, fake)
            surrenderReadFd()
            transport.onChannelAttached()
            transport.onReadClosed = failsOnceLikeProduction("the teardown failed too")
            transport.readEnabled = true

            // The readiness failure is what is raised, with the wind-down's own
            // failure suppressed onto it: the allocator is the cause and the
            // notification threw reacting to it. Here they are two exceptions;
            // on the peer-close path they are one, which is the case that used
            // to be swallowed.
            val thrown = onLoopCatching { transport.onReady(Interest.READ) }

            assertTrue(thrown is OutOfMemoryError, "expected the allocator's failure, got $thrown")
            assertEquals("no buffer for you", thrown.message)
            assertEquals(
                listOf("the teardown failed too"),
                thrown.suppressedExceptions.map { it.message },
                "the failure that ended the wind-down travels with the one that started it",
            )
            assertFalse(transport.isOpen, "the connection is still ended")
        }
    }
}
