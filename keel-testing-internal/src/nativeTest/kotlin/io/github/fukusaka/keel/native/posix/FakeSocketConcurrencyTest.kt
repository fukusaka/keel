package io.github.fukusaka.keel.native.posix

import kotlinx.cinterop.Arena
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import platform.posix.pthread_create
import platform.posix.pthread_join
import platform.posix.pthread_tVar
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the fakes promise when two threads touch them at once.
 *
 * The engine issues syscalls from its loop thread while the test thread arms
 * scripts and reads counters back. That is the arrangement every seam test is
 * in, and it is the one the fakes' old contract — "single-threaded only; wrap in
 * `synchronized` at the call site" — described as somebody else's problem. No
 * caller could take it up: the other caller is production code.
 *
 * These drive the same shape deliberately, at a volume no seam test reaches, so
 * that removing the lock is a failure rather than a possibility. Measured with
 * [FakeSocketLock.withLock] reduced to calling its block, each run alone, three
 * runs each: the two counting tests fail their assertion every time, and the
 * one-shot and ops tests take the process down every time — an unguarded
 * `ArrayList.add` from two threads corrupts the list rather than losing an
 * entry. Both are detection; neither is waiting for an unlucky interleaving.
 *
 * Run alone, because the crashing pair ends the binary and the others' verdicts
 * with it.
 *
 * They do not check ordering, because the fakes do not provide it — see the
 * [FakeSocketLock] KDoc on the difference between a whole read and a meaningful
 * one.
 *
 * **`pthread_create`, not `Worker`**: the loop these fakes serve runs on a
 * pthread (`KqueueEventLoop.start` / `EpollEventLoop.start`), so this contends
 * with the same kind of thread the real arrangement does. `NativeConcurrencyProbeTest`
 * settled the same question the same way, and `Worker` would additionally mean
 * opting in to an API whose own name calls it obsolete.
 */
@OptIn(ExperimentalForeignApi::class)
class FakeSocketConcurrencyTest {

    @Test
    fun `a counter under concurrent calls holds every one of them`() {
        val fake = FakeNativeSocket().apply { defaultRead = ReadResult.Bytes(1) }
        val shared = Contention(KIND_COUNT, socket = fake)

        contendWith(shared) {
            awaitPeer(shared.started)
            fake.readRepeatedly()
        }

        assertEquals(
            2 * CALLS_PER_THREAD,
            fake.readCalls,
            "every call must be counted; a lost increment is two threads reading the same value",
        )
    }

    @Test
    fun `a scripted response is handed to one caller only`() {
        val fake = FakeNativeSocket().apply { defaultRead = ReadResult.WouldBlock }
        // One at a time: `enqueueRead` takes a vararg, and spreading an array of
        // forty thousand copies it first.
        repeat(2 * CALLS_PER_THREAD) { fake.enqueueRead(FD, ReadResult.Bytes(1)) }
        val shared = Contention(KIND_SCRIPT, socket = fake)

        var here = 0
        contendWith(shared) {
            awaitPeer(shared.started)
            here = fake.readRepeatedly()
        }

        // Exactly the scripted number came out, each to one caller: a deque torn
        // between two threads either hands an entry out twice — more `Bytes` than
        // were queued — or drops entries and answers `WouldBlock`.
        assertEquals(
            2 * CALLS_PER_THREAD,
            here + shared.tally.value,
            "each queued response must be delivered exactly once across both threads",
        )
        fake.assertAllConsumed()
    }

    /**
     * Two readers hammering while this thread re-arms: the one-shot must be
     * taken as many times as it is armed, never more.
     *
     * Arming it once and having each side read once does not test anything —
     * the window is a few instructions wide and both sides have left it before
     * the other arrives. Measured: that shape passed every round of two hundred
     * with the lock removed. Readers that never stop reading widen the window to
     * the whole run; without the lock this one no longer reaches its assertion
     * at all, because two readers racing the same field take the process with
     * them.
     */
    @Test
    fun `a one-shot throw is taken as many times as it is armed`() {
        val fake = FakeNativeSocket().apply { defaultRead = ReadResult.WouldBlock }
        val shared = Contention(KIND_ONE_SHOT, socket = fake)
        var armed = 0

        contendWith(shared, peers = 2) {
            awaitPeer(shared.started, parties = 3)
            repeat(ONE_SHOT_ROUNDS) {
                fake.readThrowsOnce = IllegalStateException("scripted")
                armed++
                var spins = 0
                while (fake.readThrowsOnce != null) {
                    // Until a reader takes it. Bounded so a fake that stops
                    // clearing fails the assertion rather than hanging here.
                    check(++spins < ARMING_PATIENCE) { "no reader took the one-shot after $spins spins" }
                }
            }
            shared.stopped.value = 1
        }

        assertEquals(
            armed,
            shared.tally.value,
            "the one-shot must be taken once per arming; more means two readers took the same one",
        )
    }

    @Test
    fun `the ops fake counts every concurrent call`() {
        val fake = FakeNativeSocketOps()
        val shared = Contention(KIND_OPS, ops = fake)

        contendWith(shared) {
            awaitPeer(shared.started)
            repeat(CALLS_PER_THREAD) { fake.setNonBlocking(FD) }
        }

        assertEquals(2 * CALLS_PER_THREAD, fake.setNonBlockingCalls, "every call must be counted")
        assertEquals(
            2 * CALLS_PER_THREAD,
            fake.nonBlockingFds.size,
            "every call must be recorded; a torn list drops entries",
        )
    }
}

/** Which half the spawned thread runs — a `staticCFunction` cannot capture one. */
private const val KIND_COUNT = 0
private const val KIND_SCRIPT = 1
private const val KIND_ONE_SHOT = 2
private const val KIND_OPS = 3

private const val FD = 3

/**
 * High enough that an unguarded counter loses increments every run, low enough
 * that all four tests together stay well inside a second.
 */
private const val CALLS_PER_THREAD = 20_000

/** How many times the one-shot test re-arms while the readers hammer. */
private const val ONE_SHOT_ROUNDS = 200

/**
 * How long an arming waits to be taken before the test calls it stuck. Generous
 * — the readers are in a tight loop — and only there so a fake that stops
 * clearing the field fails instead of hanging.
 */
private const val ARMING_PATIENCE = 100_000_000

/**
 * Everything the spawned thread needs, reachable through the single `void*` the
 * C signature allows: which half to run, the fake to hammer, the gate both sides
 * spin on so they overlap, and a tally for what that side must report back.
 */
private class Contention(
    val kind: Int,
    val socket: FakeNativeSocket? = null,
    val ops: FakeNativeSocketOps? = null,
) {
    val started: AtomicInt = AtomicInt(0)
    val tally: AtomicInt = AtomicInt(0)

    /** Raised when the peers should stop hammering and let the join complete. */
    val stopped: AtomicInt = AtomicInt(0)
}

/** All sides in their loop together, or one finishes before another starts. */
private fun awaitPeer(started: AtomicInt, parties: Int = 2) {
    started.incrementAndGet()
    while (started.value < parties) {
        // Spin until the others arrive.
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun FakeNativeSocket.readRepeatedly(): Int {
    var delivered = 0
    memScoped {
        val byte = alloc<ByteVar>()
        repeat(CALLS_PER_THREAD) {
            if (read(FD, byte.ptr, 1) is ReadResult.Bytes) delivered++
        }
    }
    return delivered
}

@OptIn(ExperimentalForeignApi::class)
private fun FakeNativeSocket.readOnceThrew(): Boolean = memScoped {
    val byte = alloc<ByteVar>()
    runCatching { read(FD, byte.ptr, 1) }.isFailure
}

/** The spawned thread's half, dispatched on [Contention.kind]. */
private fun runPeerHalf(shared: Contention) {
    awaitPeer(shared.started, if (shared.kind == KIND_ONE_SHOT) 3 else 2)
    when (shared.kind) {
        KIND_COUNT -> shared.socket!!.readRepeatedly()
        KIND_SCRIPT -> shared.tally.addAndGet(shared.socket!!.readRepeatedly())
        KIND_ONE_SHOT ->
            while (shared.stopped.value == 0) {
                if (shared.socket!!.readOnceThrew()) shared.tally.incrementAndGet()
            }
        KIND_OPS -> repeat(CALLS_PER_THREAD) { shared.ops!!.setNonBlocking(FD) }
        else -> error("unknown contention kind ${shared.kind}")
    }
}

/**
 * Runs [shared]'s peer half on a real pthread while [hereSide] runs on this one,
 * joining before returning.
 *
 * The `StableRef` is disposed here rather than on the spawned thread so that a
 * body which throws still frees it, and so ownership is not split across the
 * thread boundary.
 */
@OptIn(ExperimentalForeignApi::class)
private inline fun contendWith(shared: Contention, peers: Int = 1, hereSide: () -> Unit) {
    val arena = Arena()
    val ref = StableRef.create(shared)
    try {
        val threads = List(peers) { arena.alloc<pthread_tVar>() }
        repeat(peers) { i ->
            val rc = pthread_create(
                threads[i].ptr,
                null,
                staticCFunction { arg ->
                    runPeerHalf(arg!!.asStableRef<Contention>().get())
                    null
                },
                ref.asCPointer(),
            )
            check(rc == 0) { "pthread_create failed: rc=$rc" }
        }
        hereSide()
        repeat(peers) { i -> pthread_join(threads[i].ptr[0], null) }
    } finally {
        ref.dispose()
        arena.clear()
    }
}
