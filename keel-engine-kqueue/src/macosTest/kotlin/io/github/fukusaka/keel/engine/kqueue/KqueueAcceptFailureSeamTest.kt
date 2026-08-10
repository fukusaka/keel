package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Host
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.native.posix.AcceptResult
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.FakeNativeSocketOps
import io.github.fukusaka.keel.native.posix.HandoffOutcome
import io.github.fukusaka.keel.native.posix.LoopParticipant
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.EBADF
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.dup
import platform.posix.errno
import platform.posix.socket
import platform.posix.usleep
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * What the kqueue accept path does when the hand-off or the construction
 * behind it fails.
 *
 * Split from [KqueueAcceptSeamTest], which covers the branches of `accept`
 * itself. These cover the other half: a worker that will never run the work,
 * one that is still stopping, and a connection whose own initialiser throws.
 * Each ends the same way if unguarded — a descriptor open for the process's
 * life, on a socket whose peer thinks it is connected.
 */
@OptIn(ExperimentalForeignApi::class)
class KqueueAcceptFailureSeamTest {

    /**
     * Creates a real but unbound `socket(AF_INET, SOCK_STREAM, 0)` fd so
     * `bindListener` and the arm calls succeed. Mirrors the sibling suite.
     */
    private fun newSentinelFd(): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "failed to create sentinel socket" }
        return fd
    }

    @Test
    fun `an accept handed to a stopped worker releases the descriptor`() = runBlocking {
        withTimeout(15.seconds) {
            // A worker's queue outlives the worker: `dispatch` takes a task
            // whatever state the loop is in, and after the final drain nothing
            // drains it again. An accepted descriptor handed over that way was
            // neither served nor released -- it stayed open until the process
            // exited, while the peer's `connect` had already succeeded and it
            // waited on a socket nobody would ever read.
            //
            // The boss loop is left running: this is one half-stopped engine,
            // not a closed one, which is the state a worker that broke out of
            // its own loop leaves behind.
            val warns = RecordingLogger(LogLevel.WARN)
            val bossLoop = KqueueEventLoop(warns)
            val workerGroup = KqueueEventLoopGroup(1, warns, DefaultAllocator)
            val sentinelFd = newSentinelFd()
            val acceptedFd = socket(AF_INET, SOCK_STREAM, 0)
            assertTrue(acceptedFd >= 0, "could not open a socket to be accepted")
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18089)
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Accepted(acceptedFd))
                enqueueAccept(sentinelFd, AcceptResult.WouldBlock)
            }
            val server = KqueuePipelinedStreamServer(
                listeners = listOf(
                    KqueuePipelinedStreamServer.Listener(sentinelFd, scriptedLocal, BindConfig()),
                ),
                bossLoop = bossLoop,
                workerGroup = workerGroup,
                logger = warns,
                pipelineInitializer = { /* no-op initializer */ },
                nativeSocket = fakeSocket,
                nativeSocketOps = FakeNativeSocketOps(),
            )
            // Both loops start inside the `try`: a pthread each, and an assert
            // failing before the `finally` is reached would leave them running
            // for the rest of the suite -- one process runs all of it.
            try {
                // The boss is deliberately not started. `acceptLoop` arms the
                // listener from inside the drive, and an unbound listen socket
                // reports readiness at once -- so a running boss would be
                // calling `accept()` on the same fake, concurrently with this
                // thread, before the drive even returns. Every count here would
                // then be a race. The drive is issued directly instead; the
                // listener fd is still released, because closing an unstarted
                // loop now drains what was queued for it.
                workerGroup.start()
                // Deliberately not armed on the boss loop. An unbound listen
                // socket reports readiness on it at once and goes on doing so,
                // so an armed listener runs this accept loop from the boss
                // thread too -- and the two threads then race for the one
                // scripted `Accepted`, which the boss can take while the worker
                // is still alive. The accept below is driven directly instead,
                // which is what this seam does everywhere else. (`acceptLoop`
                // does arm the listener on its way out, so the boss picks the
                // storm up from there -- by then the script is drained.)
                //
                // Joined and quiescent: nothing will drain this worker's queue
                // again.
                workerGroup.close()

                server.onAcceptable()
                // Snapshotted here, not read at the assertion: `acceptLoop`
                // re-arms the listener on its way out, and an unbound listen
                // socket reports readiness at once and keeps doing so, so the
                // boss thread drives this loop again for as long as the test
                // stands still. Only the count this drive left answers the
                // question.
                val callsAfterDrive = fakeSocket.acceptCalls

                val probe = dup(acceptedFd)
                val probeErrno = errno
                if (probe >= 0) close(probe)
                assertEquals(
                    -1,
                    probe,
                    "a worker that will never run this accept must not be left holding the descriptor",
                )
                assertEquals(EBADF, probeErrno, "closed, not fd-table exhaustion: the probe must fail with EBADF")
                assertEquals(
                    2,
                    callsAfterDrive,
                    "dropping one connection must not unwind the loop: the peers queued behind it are not at fault",
                )
                assertEquals(
                    1,
                    warns.messages.count { "fd=$acceptedFd" in it && "has stopped" in it },
                    "the drop is reported once, naming the descriptor: ${warns.messages}",
                )
            } finally {
                // Before the closes below, which can be handed this number: on a
                // failing run production left it open. `dup` only says the
                // number is open, not that it is still this socket -- nothing
                // here can tell the difference -- but between the assertions
                // and this line the test opens nothing, and the boss loop is
                // parked, so there is no recycling to be caught out by.
                val leftOpen = dup(acceptedFd)
                if (leftOpen >= 0) {
                    close(leftOpen)
                    close(acceptedFd)
                }
                server.close()
                bossLoop.close()
                workerGroup.close()
            }
        }
    }

    @Test
    fun `a stopped worker costs the accept callback one wait rather than one per connection`() {
        // The budget bounds a single hand-off. This loop makes as many as the
        // backlog holds, so without carrying the verdict across iterations a
        // worker stuck between "finished polling" and "quiet" would cost the
        // full wait *each* -- a listen backlog of 128 turns a 100ms bound into
        // 12.8s inside one readiness callback, with the boss loop serving no
        // other listener and draining no task for the whole of it. The bound
        // has to belong to the callback.
        val full = KqueuePipelinedStreamServer.STOPPING_WORKER_WAIT_MICROS
        val fresh = KqueuePipelinedStreamServer.DropTally()
        assertEquals(full, fresh.remainingBudget(), "the first hand-off of a callback has the whole allowance")

        // A hand-off that waits and then sees quiescence costs this thread just
        // as much as one that gives up. Keying the carry-over on expiry alone
        // let a group of stopping workers charge the callback once each.
        val afterWaiting = fresh.record(7, HandoffOutcome.FELL_BACK, waitedMicros = full * 2 / 5)
        assertEquals(
            full - full * 2 / 5,
            afterWaiting.remainingBudget(),
            "a wait that ended in quiescence still spent the allowance",
        )

        val afterHandingOver = afterWaiting.record(8, HandoffOutcome.HANDED_TO_LOOP, waitedMicros = full * 2 / 5)
        assertEquals(
            full - full * 4 / 5,
            afterHandingOver.remainingBudget(),
            "a hand-off the loop took still charges whatever this thread waited for it",
        )

        val afterGivingUp = afterHandingOver.record(9, HandoffOutcome.FELL_BACK_AFTER_EXPIRY, waitedMicros = full)
        assertEquals(
            0L,
            afterGivingUp.remainingBudget(),
            "the allowance floors at nothing rather than going negative",
        )
        assertEquals(
            0L,
            afterGivingUp.record(10, HandoffOutcome.HANDED_TO_LOOP, waitedMicros = 0).remainingBudget(),
            "and nothing gives it back",
        )
        assertEquals(9, afterGivingUp.firstGaveUpFd, "the descriptor released without the ordering is named")
        assertEquals(7, afterGivingUp.firstDroppedFd, "and it is not the one the clean drop reports")
    }

    @Test
    fun `several accepts dropped by one stopped worker are reported once`() = runBlocking {
        withTimeout(15.seconds) {
            // One line per dropped connection turns a worker that stays down
            // into a log flood at the accept rate, which is what this callback
            // produces for as long as peers keep arriving.
            val warns = RecordingLogger(LogLevel.WARN)
            val bossLoop = KqueueEventLoop(warns)
            val workerGroup = KqueueEventLoopGroup(1, warns, DefaultAllocator)
            val sentinelFd = newSentinelFd()
            val first = socket(AF_INET, SOCK_STREAM, 0)
            val second = socket(AF_INET, SOCK_STREAM, 0)
            assertTrue(first >= 0 && second >= 0, "could not open sockets to be accepted")
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18090)
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Accepted(first))
                enqueueAccept(sentinelFd, AcceptResult.Accepted(second))
                enqueueAccept(sentinelFd, AcceptResult.WouldBlock)
            }
            val server = KqueuePipelinedStreamServer(
                listeners = listOf(
                    KqueuePipelinedStreamServer.Listener(sentinelFd, scriptedLocal, BindConfig()),
                ),
                bossLoop = bossLoop,
                workerGroup = workerGroup,
                logger = warns,
                pipelineInitializer = { /* no-op initializer */ },
                nativeSocket = fakeSocket,
                nativeSocketOps = FakeNativeSocketOps(),
            )
            try {
                // The boss is deliberately not started. `acceptLoop` arms the
                // listener from inside the drive, and an unbound listen socket
                // reports readiness at once -- so a running boss would be
                // calling `accept()` on the same fake, concurrently with this
                // thread, before the drive even returns. Every count here would
                // then be a race. The drive is issued directly instead; the
                // listener fd is still released, because closing an unstarted
                // loop now drains what was queued for it.
                workerGroup.start()
                workerGroup.close()

                server.onAcceptable()

                assertEquals(
                    1,
                    warns.messages.count { "has stopped" in it },
                    "two drops, one line: ${warns.messages}",
                )
                assertTrue(
                    warns.messages.any { "2 accepted connection(s)" in it },
                    "and it must say how many, not just name one: ${warns.messages}",
                )
                for (fd in listOf(first, second)) {
                    val probe = dup(fd)
                    if (probe >= 0) close(probe)
                    assertEquals(-1, probe, "every dropped descriptor is released, not only the first")
                }
            } finally {
                for (fd in listOf(first, second)) {
                    val leftOpen = dup(fd)
                    if (leftOpen >= 0) {
                        close(leftOpen)
                        close(fd)
                    }
                }
                server.close()
                bossLoop.close()
                workerGroup.close()
            }
        }
    }

    @Test
    fun `two accepts routed to a worker stuck stopping share one allowance`() = runBlocking {
        withTimeout(30.seconds) {
            // The wiring the change exists for, driven end to end: the tally is
            // only worth having if `acceptLoop` hands each hand-off what is
            // left of it. Holding the worker inside its stop sweep is what
            // makes that observable -- it is the one state where the hand-off
            // waits at all, and the seam cannot reach it through `close()`
            // alone, which returns only once the sweep is done.
            val warns = RecordingLogger(LogLevel.WARN)
            val bossLoop = KqueueEventLoop(warns)
            val workerGroup = KqueueEventLoopGroup(1, warns, DefaultAllocator)
            val sentinelFd = newSentinelFd()
            val first = socket(AF_INET, SOCK_STREAM, 0)
            val second = socket(AF_INET, SOCK_STREAM, 0)
            assertTrue(first >= 0 && second >= 0, "could not open sockets to be accepted")
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18091)
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Accepted(first))
                enqueueAccept(sentinelFd, AcceptResult.Accepted(second))
                enqueueAccept(sentinelFd, AcceptResult.WouldBlock)
            }
            val server = KqueuePipelinedStreamServer(
                listeners = listOf(
                    KqueuePipelinedStreamServer.Listener(sentinelFd, scriptedLocal, BindConfig()),
                ),
                bossLoop = bossLoop,
                workerGroup = workerGroup,
                logger = warns,
                pipelineInitializer = { /* no-op initializer */ },
                nativeSocket = fakeSocket,
                nativeSocketOps = FakeNativeSocketOps(),
            )
            val release = AtomicInt(0)
            val wedge = WedgingParticipant(release)
            try {
                // The boss is deliberately not started. `acceptLoop` arms the
                // listener from inside the drive, and an unbound listen socket
                // reports readiness at once -- so a running boss would be
                // calling `accept()` on the same fake, concurrently with this
                // thread, before the drive even returns. Every count here would
                // then be a race. The drive is issued directly instead; the
                // listener fd is still released, because closing an unstarted
                // loop now drains what was queued for it.
                workerGroup.start()
                workerGroup.at(0).addParticipant(wedge)
                // Closing from another thread: the sweep runs the participant
                // above, which does not return until this test says so, so the
                // worker sits published-as-finished and never quiescent.
                val closer = launch(Dispatchers.Default) { workerGroup.close() }
                val armed = TimeSource.Monotonic.markNow()
                while (!workerGroup.at(0).isFinishing() && armed.elapsedNow() < WEDGE_SETUP_BUDGET) {
                    usleep(POLL_MICROS)
                }
                assertTrue(workerGroup.at(0).isFinishing(), "premise: the worker is stopping and wedged in its sweep")

                val startedAt = TimeSource.Monotonic.markNow()
                server.onAcceptable()
                val spent = startedAt.elapsedNow()

                // One allowance for the callback, not one per connection: two
                // hand-offs, each of which would wait the full budget on its
                // own. The margin is half a budget, so this cannot fail for
                // scheduling noise -- the failure it is looking for doubles it.
                assertTrue(
                    spent < ONE_AND_A_HALF_BUDGETS,
                    "two hand-offs to a stuck worker must share one allowance, not take one each ($spent)",
                )
                for (fd in listOf(first, second)) {
                    val probe = dup(fd)
                    if (probe >= 0) close(probe)
                    assertEquals(-1, probe, "both descriptors are released rather than left to the wedged worker")
                }
                release.value = 1
                closer.join()
            } finally {
                release.value = 1
                for (fd in listOf(first, second)) {
                    val leftOpen = dup(fd)
                    if (leftOpen >= 0) {
                        close(leftOpen)
                        close(fd)
                    }
                }
                server.close()
                bossLoop.close()
                workerGroup.close()
            }
        }
    }

    /**
     * A participant whose stop notification does not return until released,
     * holding its loop between "finished polling" and "quiet" — the only
     * window in which the accept hand-off waits at all.
     */
    private class WedgingParticipant(private val release: AtomicInt) : LoopParticipant {
        override fun onLoopStopped() {
            while (release.value == 0) usleep(POLL_MICROS)
        }
    }

    @Test
    fun `a connection whose initializer throws is closed rather than left unread`() = runBlocking {
        withTimeout(15.seconds) {
            // The pipeline initializer is user code, and it runs after the
            // transport has joined the loop. A throw there skips
            // `readEnabled = true`, so the connection is in the registry, holds
            // its descriptor, and is never read -- and nobody else holds the
            // channel to close it, so it stays that way for the process's life.
            // The peer's `connect` has already succeeded. Same shape as an
            // accept handed to a dead worker, reached through the one path that
            // runs somebody else's code.
            val warns = RecordingLogger(LogLevel.WARN)
            val bossLoop = KqueueEventLoop(warns)
            val workerGroup = KqueueEventLoopGroup(1, warns, DefaultAllocator)
            val sentinelFd = newSentinelFd()
            val accepted = socket(AF_INET, SOCK_STREAM, 0)
            assertTrue(accepted >= 0, "could not open a socket to be accepted")
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18092)
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Accepted(accepted))
                enqueueAccept(sentinelFd, AcceptResult.WouldBlock)
            }
            val server = KqueuePipelinedStreamServer(
                listeners = listOf(
                    KqueuePipelinedStreamServer.Listener(sentinelFd, scriptedLocal, BindConfig()),
                ),
                bossLoop = bossLoop,
                workerGroup = workerGroup,
                logger = warns,
                pipelineInitializer = { error("the initializer for this connection failed") },
                nativeSocket = fakeSocket,
                nativeSocketOps = FakeNativeSocketOps(),
            )
            try {
                // The boss is deliberately not started. `acceptLoop` arms the
                // listener from inside the drive, and an unbound listen socket
                // reports readiness at once -- so a running boss would be
                // calling `accept()` on the same fake, concurrently with this
                // thread, before the drive even returns. Every count here would
                // then be a race. The drive is issued directly instead; the
                // listener fd is still released, because closing an unstarted
                // loop now drains what was queued for it.
                workerGroup.start()

                server.onAcceptable()
                // Read before the wait below. `acceptLoop` re-arms the listener
                // on its way out, and an unbound listen socket reports
                // readiness at once and keeps doing so, so the boss thread
                // drives this loop again for as long as the test stands still.
                // The count that answers "did one failure unwind the batch" is
                // the one the drive itself left.
                val callsAfterDrive = fakeSocket.acceptCalls

                // The hand-off is to a live worker, so the construction runs on
                // its thread: wait for the descriptor to go rather than reading
                // it straight away.
                val deadline = TimeSource.Monotonic.markNow()
                while (deadline.elapsedNow() < CLOSE_BUDGET) {
                    val probe = dup(accepted)
                    if (probe < 0) break
                    close(probe)
                    usleep(CLOSE_POLL_MICROS)
                }
                val probe = dup(accepted)
                if (probe >= 0) close(probe)
                assertEquals(
                    -1,
                    probe,
                    "a connection nobody will read and nobody holds must not keep its descriptor",
                )
                assertEquals(
                    2,
                    callsAfterDrive,
                    "and one failing initializer must not stop the loop serving the peers behind it",
                )
            } finally {
                val leftOpen = dup(accepted)
                if (leftOpen >= 0) {
                    close(leftOpen)
                    close(accepted)
                }
                server.close()
                bossLoop.close()
                workerGroup.close()
            }
        }
    }

    private companion object {
        /** Poll interval while waiting for another thread to reach a state. */
        const val POLL_MICROS: UInt = 1_000u

        /** Poll interval while waiting for a worker thread to release a descriptor. */
        const val CLOSE_POLL_MICROS: UInt = 1_000u

        /** Wall-clock bound on that wait; generous, since it only has to exclude a hang. */
        val CLOSE_BUDGET = 10.seconds

        /** Long enough for the closing thread to publish "finished" on a loaded runner. */
        val WEDGE_SETUP_BUDGET = 10.seconds

        /**
         * The ceiling for two hand-offs sharing one allowance. Half a budget of
         * slack over the one wait they should cost, and half a budget short of
         * the two the regression would cost.
         */
        val ONE_AND_A_HALF_BUDGETS = 150.milliseconds
    }
}
