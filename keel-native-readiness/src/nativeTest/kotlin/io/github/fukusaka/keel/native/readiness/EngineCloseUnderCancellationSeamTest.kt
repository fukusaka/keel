@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.FakeNativeSocketOps
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * What a close owes when the caller asking for it does not survive it.
 *
 * `close()` commits before it does any of the work: it sets its closed flag
 * first, so every later caller returns straight away believing the teardown
 * happened. The release after that write has to run whatever the calling
 * coroutine is told, because there is no second caller left to retry it — the
 * same reason the boss/group release already guards its two steps against each
 * other rather than letting the first throw skip the second.
 *
 * The step between the two is the join, and it is a suspension point, so it
 * answers to the *caller's* job rather than the engine's. Three cases here,
 * because that one fact cuts three ways and a fix can satisfy one while
 * breaking another — each fails on its own mutation, measured:
 *
 * 1. A cancelled caller must not take the release down with it. Before, the
 *    join threw and left the accept loop and every loop in the worker group
 *    holding their descriptors with nobody able to ask again.
 * 2. That caller must still be told it was cancelled, after the release rather
 *    than instead of it.
 * 3. The join must stay interruptible — both so a caller's timeout still bounds
 *    the shutdown, and, in the case below it, so a caller that is *itself* a
 *    child of the engine is not waiting for a job it is keeping alive.
 *
 * Written against the abstract engine over loop doubles, which is where the
 * flag and the release both live; the two shipped readiness engines inherit
 * this `close()` unchanged.
 *
 * Each case drives a teardown, so each is bounded ([withTimeout] /
 * [withTimeoutOrNull] under `runBlocking`, wall-clock). What that bound rests
 * on: these doubles free memory and never block a thread, so a `close()` that
 * goes wrong here suspends and lets the bound fire. A double that blocked
 * instead would hang the case rather than fail it, since the undispatched
 * starts below put `close()` on this thread.
 */
@OptIn(ExperimentalForeignApi::class)
internal class EngineCloseUnderCancellationSeamTest : AbstractReadinessEventLoopFixture() {

    private class FakeWorkerGroup(loop: FakeLoop) : AbstractReadinessEventLoopGroup<FakeLoop>(arrayOf(loop))

    /** The abstract engine over doubles: its `close()` is the code under test. */
    private class TestEngine(
        boss: FakeLoop,
        worker: FakeLoop,
    ) : AbstractReadinessEngine("TestEngine", IoEngineConfig(), FakeNativeSocket(), FakeNativeSocketOps()) {
        override val bossLoop: AbstractReadinessEventLoop = boss
        override val workerGroup: AbstractReadinessEventLoopGroup<*> = FakeWorkerGroup(worker)
    }

    @Test
    fun `a close whose caller is cancelled still gives the loops back`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            val boss = FakeLoop()
            val worker = FakeLoop()
            val engine = TestEngine(boss, worker)

            try {
                // Cancelled before it asks, which is what makes this
                // deterministic rather than a race: the join's fast path ends
                // in `ensureActive()` on the calling context, so an already
                // cancelled caller reaches the throw every run. A caller
                // cancelled a moment later lands in the same place by the
                // slower route.
                //
                // Its own scope, and undispatched, so the whole of `close()`
                // runs on this thread before `await()` below reads the loops:
                // the two would otherwise be a race of their own.
                val outcome = CompletableDeferred<Result<Unit>>()
                CoroutineScope(Job()).launch(start = CoroutineStart.UNDISPATCHED) {
                    coroutineContext.job.cancel()
                    outcome.complete(runCatching { engine.close() })
                }
                val callerGot = outcome.await()

                // The release first, because it is what this case is for: a
                // run against the unfixed close must fail naming the loops,
                // not something downstream of them.
                //
                // The scratch, not a record of the call: it is the resource
                // the release exists to give back, and the fixture's own
                // teardown reads the same field. Both of this case's two,
                // because the release is two statements and a fix that
                // covered only the first would leave the group holding
                // everything -- and in a shipped engine the group is N loops,
                // not the one this double gives it.
                assertFalse(
                    boss.writevScratch.owned,
                    "the boss loop is released even though the caller was cancelled -- the flag is already set, " +
                        "so nobody can ask for this teardown a second time",
                )
                assertFalse(
                    worker.writevScratch.owned,
                    "and the worker group behind it, which a release that stopped at the boss would strand",
                )

                // Then what the caller was handed. It still learns it was
                // cancelled -- the release does not swallow that, it only
                // stops it arriving first. Pinned because the alternative is
                // silent: a caller told nothing would carry on through a
                // shutdown it no longer owns.
                val handedBack = callerGot.exceptionOrNull()
                assertTrue(
                    handedBack is CancellationException,
                    "the cancellation reaches the caller, after the release rather than instead of it, " +
                        "got: $handedBack",
                )
            } finally {
                // Idempotent (the scratch's own free checks first), so this
                // costs nothing when the engine did its job and keeps a
                // failing run from leaking on every repeat.
                boss.close()
                worker.close()
            }
        }
    }

    @Test
    fun `a close whose caller runs out of time does not wait for the engine's children`() = runBlocking {
        val boss = FakeLoop()
        val worker = FakeLoop()
        val engine = TestEngine(boss, worker)
        // A child that will not be hurried: the join is over application
        // coroutines, so whatever they do the shutdown inherits. Held here so
        // the case can wait for it rather than leave it running.
        //
        // Waited for before closing, and that is what gives this case its
        // teeth. Without the signal the close can reach the join before this
        // child has started, and a child cancelled before it runs makes the
        // join return at once -- measured: the case passed against a join that
        // could not be interrupted, because there was nothing to wait for.
        val childRunning = CompletableDeferred<Unit>()
        val stubborn = engine.launch(Dispatchers.Default) {
            withContext(NonCancellable) {
                childRunning.complete(Unit)
                delay(CHILD_UNWIND_MS)
            }
        }
        childRunning.await()

        try {
            // The budget that has to hold. Make the join uninterruptible and
            // this returns after the child does instead -- measured, and the
            // reason it matters is that the server's own shutdown puts
            // `close()` in a `finally` with no budget of its own, so a caller's
            // timeout is the only bound there is.
            val startedAt = TimeSource.Monotonic.markNow()
            withTimeoutOrNull(CALLER_BUDGET_MS) { engine.close() }
            val elapsed = startedAt.elapsedNow()

            assertTrue(
                elapsed < CHILD_UNWIND_MS.milliseconds,
                "the caller's timeout still ends its wait; it took $elapsed against a child that takes " +
                    "$CHILD_UNWIND_MS ms",
            )
            assertFalse(
                boss.writevScratch.owned,
                "and the loops are released on the way out, which is the whole point of not simply giving up",
            )
            assertFalse(worker.writevScratch.owned, "the worker group too")
        } finally {
            // Awaited, not abandoned: it holds no loop, but leaving a live
            // coroutine behind lets it outlive the case and land in another.
            stubborn.join()
            boss.close()
            worker.close()
        }
    }

    @Test
    fun `a close called from the engine's own scope still gives the loops back`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The shape the engine's own contract asks callers to use: work
            // that touches this engine's channels is launched as a child of
            // this engine. So a shutdown path reaching `close()` from inside
            // one is a supported caller, not a misuse -- and it is the caller
            // that the join cannot be allowed to wait for.
            //
            // The wait would be circular: `cancelAndJoin` cancels this engine's
            // job and then waits for it, and a job does not complete while a
            // child of it is still running -- this caller being that child.
            // Made unbreakable, that wait is permanent: the flag is already
            // set, so no second caller can take over, and no timeout above can
            // cut it, which is what makes it worse than the throw it replaced.
            val boss = FakeLoop()
            val worker = FakeLoop()
            val engine = TestEngine(boss, worker)

            try {
                // Undispatched, so `close()` runs inline as far as it gets. If
                // it parks in the join it never comes back and the assertions
                // below read loops nobody released; if it does not, it has
                // finished by the time `launch` returns.
                engine.launch(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { engine.close() }
                }

                assertFalse(
                    boss.writevScratch.owned,
                    "a caller that is itself a child of the engine still gets the accept loop released -- " +
                        "waiting for the job it belongs to would never end",
                )
                assertFalse(
                    worker.writevScratch.owned,
                    "and the worker group behind it",
                )
            } finally {
                boss.close()
                worker.close()
            }
        }
    }

    private companion object {
        /**
         * How long the stubborn child takes to unwind, and how long the
         * caller allows. Far apart on purpose: the case asks which of the
         * two ended the wait, so it only has to tell them apart, and a
         * loaded runner that stretches the shorter one has the whole gap
         * before it could reach the wrong answer.
         */
        const val CHILD_UNWIND_MS = 2_000L
        const val CALLER_BUDGET_MS = 300L
    }
}
