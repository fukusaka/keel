package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Host
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.native.posix.AcceptResult
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.FakeNativeSocketOps
import io.github.fukusaka.keel.native.posix.HandoffOutcome
import io.github.fukusaka.keel.native.posix.LoopParticipant
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.testing.InjectedFault
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * What the epoll accept path does when the hand-off or the construction
 * behind it fails.
 *
 * Split from [EpollAcceptSeamTest], which covers the branches of `accept`
 * itself. These cover the other half: a worker that will never run the work,
 * one that is still stopping, and a connection whose own initialiser throws.
 * Each ends the same way if unguarded — a descriptor open for the process's
 * life, on a socket whose peer thinks it is connected.
 */
@OptIn(ExperimentalForeignApi::class)
class EpollAcceptFailureSeamTest {

    /**
     * Whether [fd] still names an open descriptor, without keeping the
     * duplicate it asks with.
     */
    private fun stillOpen(fd: Int): Boolean {
        val probe = dup(fd)
        if (probe < 0) return false
        close(probe)
        return true
    }

    /**
     * Creates a real but unbound `socket(AF_INET, SOCK_STREAM, 0)` fd so the
     * arm calls succeed. Mirrors the sibling suite.
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
            // The state under test is one half-stopped engine, not a closed
            // one -- what a worker that broke out of its own loop leaves
            // behind. Only the worker has to be stopped for that; the accept
            // is driven from this thread, so the boss stays unstarted (see the
            // note in the try below).
            val warns = RecordingLogger(LogLevel.WARN)
            val bossLoop = EpollEventLoop(warns)
            val workerGroup = EpollEventLoopGroup(1, warns, DefaultAllocator)
            val sentinelFd = newSentinelFd()
            val acceptedFd = socket(AF_INET, SOCK_STREAM, 0)
            assertTrue(acceptedFd >= 0, "could not open a socket to be accepted")
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18189)
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Accepted(acceptedFd))
                enqueueAccept(sentinelFd, AcceptResult.WouldBlock)
            }
            val server = EpollPipelinedStreamServer(
                listeners = listOf(
                    EpollPipelinedStreamServer.Listener(sentinelFd, scriptedLocal, BindConfig()),
                ),
                bossLoop = bossLoop,
                workerGroup = workerGroup,
                logger = warns,
                pipelineInitializer = { /* no-op initializer */ },
                nativeSocket = fakeSocket,
                nativeSocketOps = FakeNativeSocketOps(),
            )
            // The worker starts inside the `try`: it is a pthread, and an
            // assert failing before the `finally` is reached would leave it
            // running for the rest of the suite -- one process runs all of it.
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
                // Joined and quiescent: nothing will drain this worker's queue
                // again.
                workerGroup.close()

                server.onAcceptable()
                // Snapshotted rather than read at the assertion. Nothing
                // drives this loop again while the boss stays unstarted, so the
                // two are equal today -- but the count belongs to the drive,
                // and reading it there keeps it that way if a later change
                // gives this test a running boss again.
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
        val full = EpollPipelinedStreamServer.STOPPING_WORKER_WAIT_MICROS
        val fresh = EpollPipelinedStreamServer.DropTally()
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
            val bossLoop = EpollEventLoop(warns)
            val workerGroup = EpollEventLoopGroup(1, warns, DefaultAllocator)
            val sentinelFd = newSentinelFd()
            val first = socket(AF_INET, SOCK_STREAM, 0)
            val second = socket(AF_INET, SOCK_STREAM, 0)
            assertTrue(first >= 0 && second >= 0, "could not open sockets to be accepted")
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18190)
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Accepted(first))
                enqueueAccept(sentinelFd, AcceptResult.Accepted(second))
                enqueueAccept(sentinelFd, AcceptResult.WouldBlock)
            }
            val server = EpollPipelinedStreamServer(
                listeners = listOf(
                    EpollPipelinedStreamServer.Listener(sentinelFd, scriptedLocal, BindConfig()),
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
            val bossLoop = EpollEventLoop(warns)
            val workerGroup = EpollEventLoopGroup(1, warns, DefaultAllocator)
            val sentinelFd = newSentinelFd()
            val first = socket(AF_INET, SOCK_STREAM, 0)
            val second = socket(AF_INET, SOCK_STREAM, 0)
            assertTrue(first >= 0 && second >= 0, "could not open sockets to be accepted")
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18191)
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Accepted(first))
                enqueueAccept(sentinelFd, AcceptResult.Accepted(second))
                enqueueAccept(sentinelFd, AcceptResult.WouldBlock)
            }
            val server = EpollPipelinedStreamServer(
                listeners = listOf(
                    EpollPipelinedStreamServer.Listener(sentinelFd, scriptedLocal, BindConfig()),
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
    fun `a Channel-mode accept whose socket cannot be prepared releases the descriptor`() = runBlocking {
        withTimeout(15.seconds) {
            // The three calls before the transport exists all throw on a failed
            // syscall, and a peer that resets between `accept()` returning and
            // the address query is enough to get one. The throw reaches the
            // accept loop, which logs, backs off and retries -- so a descriptor
            // left here is one per accept until the table is full.
            val sentinelFd = newSentinelFd()
            val doomed = socket(AF_INET, SOCK_STREAM, 0)
            assertTrue(doomed >= 0, "could not open a socket to be accepted")
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18193)
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Accepted(doomed))
            }
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueBindListener(sentinelFd)
                enqueueLocalAddress(sentinelFd, scriptedLocal)
                setNonBlockingThrowsOnce = IllegalStateException("fcntl(F_SETFL, O_NONBLOCK) failed: boom")
            }
            val warns = RecordingLogger(LogLevel.WARN)
            val engine = EpollEngine(
                config = IoEngineConfig(threads = 1, loggerFactory = { warns }),
                nativeSocket = fakeSocket,
                nativeSocketOps = fakeOps,
            )
            try {
                val server = engine.bind(
                    InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 0),
                    BindConfig(),
                )

                assertFailsWith<IllegalStateException> { server.accept() }

                assertFalse(
                    stillOpen(doomed),
                    "setup did not finish, so nothing owns that descriptor and this must release it",
                )
                // The release is only half of it, and the raised exception does
                // not name the descriptor -- this is what lets an operator tie
                // the failure to the connection it lost.
                assertEquals(
                    1,
                    warns.messages.count { "preparing an accepted socket failed" in it && "fd=$doomed" in it },
                    "the drop is reported, naming the descriptor: ${warns.messages}",
                )
                server.close()
            } catch (t: Throwable) {
                close(sentinelFd)
                throw t
            } finally {
                engine.close()
            }
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
            val bossLoop = EpollEventLoop(warns)
            val workerGroup = EpollEventLoopGroup(1, warns, DefaultAllocator)
            val sentinelFd = newSentinelFd()
            val first = socket(AF_INET, SOCK_STREAM, 0)
            val second = socket(AF_INET, SOCK_STREAM, 0)
            assertTrue(first >= 0 && second >= 0, "could not open sockets to be accepted")
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18192)
            // Two, because one failing initializer must not cost the peer
            // behind it. Neither the accept loop nor the worker's drain is
            // what this is about -- the guard catches, so nothing is thrown
            // for either of them to contain, and the loop goes round before
            // the worker runs anything either way. What the second descriptor
            // answers is narrower: that the guard is per connection, and not
            // a one-shot that stops guarding after it has fired once.
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Accepted(first))
                enqueueAccept(sentinelFd, AcceptResult.Accepted(second))
                enqueueAccept(sentinelFd, AcceptResult.WouldBlock)
            }
            val server = EpollPipelinedStreamServer(
                listeners = listOf(
                    EpollPipelinedStreamServer.Listener(sentinelFd, scriptedLocal, BindConfig()),
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

                // The hand-off is to a live worker, so the construction runs on
                // its thread: wait for the descriptors to go rather than
                // reading them straight away. Both, because the second is the
                // connection the guard has to still be guarding after it has
                // fired once.
                //
                // The worker group is closed before the probes, so nothing can
                // be handed a descriptor number these are about to test. The
                // sibling tests get that from a parked boss; this one has a
                // live worker until it is stopped.
                val deadline = TimeSource.Monotonic.markNow()
                while (deadline.elapsedNow() < CLOSE_BUDGET) {
                    if (!stillOpen(first) && !stillOpen(second)) break
                    usleep(CLOSE_POLL_MICROS)
                }
                workerGroup.close()

                assertFalse(
                    stillOpen(first),
                    "a connection nobody will read and nobody holds must not keep its descriptor",
                )
                assertFalse(
                    stillOpen(second),
                    "and the guard is per connection: it must still guard the peer queued behind the first",
                )
                // The release is only half of it. Reporting is what tells an
                // operator which connection went and why, and it is the half a
                // throwing teardown could silently take away.
                assertEquals(
                    2,
                    warns.messages.count { "initialising an accepted connection failed" in it },
                    "each dropped connection is reported: ${warns.messages}",
                )
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
    fun `a drop whose own release throws is still reported`() = runBlocking {
        withTimeout(15.seconds) {
            // Each guard reports before it releases, because the release is
            // itself a throw source: the transport's teardown re-raises what
            // its stages failed with. Reported after, a teardown that threw
            // would take the cause with it and leave the operator the generic
            // drain warning the guard exists to replace.
            //
            // Reachable through the drain stage, and only from the initializer
            // guard: an initializer that writes before it fails leaves the
            // flush deferred -- it runs on the worker's own thread, so the
            // flush is queued rather than performed -- and the teardown two
            // lines later finds it and drains it inline. The attach guard
            // releases through the same teardown and so has the same exposure;
            // what it lacks is a way in, since nothing on that path throws
            // today. The construction guard releases through `closeFdSafely`,
            // which reports rather than throws.
            val warns = RecordingLogger(LogLevel.WARN)
            val bossLoop = EpollEventLoop(warns)
            val workerGroup = EpollEventLoopGroup(1, warns, DefaultAllocator)
            val sentinelFd = newSentinelFd()
            val doomed = socket(AF_INET, SOCK_STREAM, 0)
            assertTrue(doomed >= 0, "could not open a socket to be accepted")
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18194)
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Accepted(doomed))
                enqueueAccept(sentinelFd, AcceptResult.WouldBlock)
            }
            val server = EpollPipelinedStreamServer(
                listeners = listOf(
                    EpollPipelinedStreamServer.Listener(sentinelFd, scriptedLocal, BindConfig()),
                ),
                bossLoop = bossLoop,
                workerGroup = workerGroup,
                logger = warns,
                pipelineInitializer = { channel ->
                    val greeting = DefaultAllocator.allocate(GREETING_BYTES)
                        .also { it.writerIndex = GREETING_BYTES }
                    channel.pipeline.requestWriteAndFlush(greeting)
                    // Armed here rather than before the drive, so nothing but
                    // this connection's own teardown can consume it.
                    fakeSocket.flushThrowsOnce = InjectedFault("the deferred flush failed")
                    error("the initializer for this connection failed")
                },
                nativeSocket = fakeSocket,
                nativeSocketOps = FakeNativeSocketOps(),
            )
            try {
                workerGroup.start()

                server.onAcceptable()

                val deadline = TimeSource.Monotonic.markNow()
                while (deadline.elapsedNow() < CLOSE_BUDGET && stillOpen(doomed)) {
                    usleep(CLOSE_POLL_MICROS)
                }
                workerGroup.close()

                // The seam reached the drain. Without this the release never
                // threw and the assertion below holds against a build that
                // reports afterwards.
                assertEquals(
                    null,
                    fakeSocket.flushThrowsOnce,
                    "the teardown must have drained the deferred flush for this test to mean anything",
                )
                assertEquals(
                    1,
                    warns.messages.count { "initialising an accepted connection failed" in it },
                    "a release that threw must not take the report of why with it: ${warns.messages}",
                )
                assertFalse(
                    stillOpen(doomed),
                    "and the descriptor still goes: the teardown closes it whatever its stages did",
                )
            } finally {
                val leftOpen = dup(doomed)
                if (leftOpen >= 0) {
                    close(leftOpen)
                    close(doomed)
                }
                server.close()
                bossLoop.close()
                workerGroup.close()
            }
        }
    }

    @Test
    fun `a Channel-mode accept whose bind config initialiser throws releases the descriptor`() = runBlocking {
        withTimeout(15.seconds) {
            // `BindConfig.initializeConnection` is an `open fun`, so this is the
            // caller's code running between the attach and the return. A throw
            // there leaves the connection joined to the loop, holding its
            // descriptor and never read, and the channel it would be closed
            // through has not been returned to anyone yet.
            val warns = RecordingLogger(LogLevel.WARN)
            val sentinelFd = newSentinelFd()
            val doomed = socket(AF_INET, SOCK_STREAM, 0)
            assertTrue(doomed >= 0, "could not open a socket to be accepted")
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18196)
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Accepted(doomed))
            }
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueBindListener(sentinelFd)
                enqueueLocalAddress(sentinelFd, scriptedLocal)
            }
            val engine = EpollEngine(
                config = IoEngineConfig(threads = 1, loggerFactory = { warns }),
                nativeSocket = fakeSocket,
                nativeSocketOps = fakeOps,
            )
            try {
                val server = engine.bind(
                    InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 0),
                    FailingInitializerBindConfig,
                )

                val raised = assertFailsWith<InjectedFault> { server.accept() }
                // Named rather than typed: the release runs a teardown that
                // re-raises its own stage failures, and the caller must be told
                // what it asked about rather than what the cleanup hit.
                assertEquals(
                    "the bind config initialiser for this connection failed",
                    raised.message,
                    "the caller waiting on accept() is told the initialiser's own failure",
                )

                // The transport exists, so the release is its teardown -- handed
                // to the worker loop rather than run here, unlike the
                // setup-window guard's raw close. Waited for rather than read
                // straight away.
                val deadline = TimeSource.Monotonic.markNow()
                while (deadline.elapsedNow() < CLOSE_BUDGET && stillOpen(doomed)) {
                    usleep(CLOSE_POLL_MICROS)
                }
                assertFalse(
                    stillOpen(doomed),
                    "a connection nobody holds and nothing will read must not keep its descriptor",
                )
                assertEquals(
                    1,
                    warns.messages.count { "initialising an accepted connection failed" in it && "fd=$doomed" in it },
                    "and the drop is reported, naming the descriptor: ${warns.messages}",
                )
                server.close()
            } catch (t: Throwable) {
                close(sentinelFd)
                throw t
            } finally {
                engine.close()
            }
        }
    }

    /** A bind config whose connection initialiser always fails. */
    private object FailingInitializerBindConfig : BindConfig() {
        override fun initializeConnection(channel: PipelinedChannel): Unit =
            throw InjectedFault("the bind config initialiser for this connection failed")
    }

    @Test
    fun `a Channel-mode accept whose worker swept before the attach releases the descriptor`() = runBlocking {
        withTimeout(15.seconds) {
            // The worker's ledgers close between the accept and the attach, so
            // `joinLoop` refuses and the connection is joined to nothing. Every
            // Channel-mode guard releases and raises; what is particular here is
            // that the transport exists but joined nothing, so its teardown runs
            // inline on this thread rather than being handed to a live loop --
            // which is why the descriptor is gone by the assertion with no wait.
            val warns = RecordingLogger(LogLevel.WARN)
            val bossLoop = EpollEventLoop(warns)
            val sweptGroup = EpollEventLoopGroup(1, warns, DefaultAllocator)
            val sentinelFd = newSentinelFd()
            val doomed = socket(AF_INET, SOCK_STREAM, 0)
            assertTrue(doomed >= 0, "could not open a socket to be accepted")
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18197)
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Accepted(doomed))
            }
            val server = EpollStreamServer(
                serverFd = sentinelFd,
                bossLoop = bossLoop,
                workerGroup = sweptGroup,
                localAddress = scriptedLocal,
                bindConfig = BindConfig(),
                logger = warns,
                nativeSocket = fakeSocket,
                nativeSocketOps = FakeNativeSocketOps(),
            )
            try {
                // Started and closed: the sweep runs, so the ledgers are shut
                // and every later join is refused. The same state the sibling
                // hand-off tests build for a stopped worker.
                sweptGroup.start()
                sweptGroup.close()

                assertFailsWith<CancellationException> { server.accept() }

                assertFalse(
                    stillOpen(doomed),
                    "a connection joined to nothing must not be left holding its descriptor",
                )
                // Pins which branch answered: the raised cancellation alone
                // cannot say, since the registration path raises the same type.
                assertEquals(
                    1,
                    warns.messages.count { "the EventLoop stopped while accepting" in it && "fd=$doomed" in it },
                    "the drop is reported from the accept side, naming the descriptor: ${warns.messages}",
                )
                server.close()
            } catch (t: Throwable) {
                close(sentinelFd)
                throw t
            } finally {
                val leftOpen = dup(doomed)
                if (leftOpen >= 0) {
                    close(leftOpen)
                    close(doomed)
                }
                bossLoop.close()
                sweptGroup.close()
            }
        }
    }

    private companion object {
        /** Poll interval while waiting for another thread to reach a state. */
        const val POLL_MICROS: UInt = 1_000u

        /** Payload an initializer writes before failing, to leave a flush deferred. */
        const val GREETING_BYTES: Int = 8

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
