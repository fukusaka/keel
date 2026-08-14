package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.core.Host
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IpAddress
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
 * [FakeSocketLock.withLock] reduced to calling its block, each test run alone,
 * twenty runs on each host: all four are caught 20 of 20 on macosArm64 and on
 * linuxX64. Three fail their assertion; the ops test mostly takes the process
 * down instead, because an unguarded `MutableList.add` from two threads
 * corrupts the list rather than losing an entry (macOS 18 crashes / 2 failures,
 * Linux 14 / 6). Both are detection.
 *
 * Run alone, because a crash ends the binary and the other verdicts with it.
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
     * Readers hammering while this thread re-arms: the throw must be taken as
     * many times as it is armed, never more.
     *
     * Two earlier shapes had no teeth, and both failures are worth keeping:
     * arming once and reading once from each side leaves a window a few
     * instructions wide, and passed 200 rounds of 200 under the mutation; then
     * hammering readers still passed 132 runs of 133 on macOS, because each
     * iteration paid for a `memScoped` and a `Result` around a critical section
     * of three instructions. The readers now allocate once and catch directly,
     * and there are [ONE_SHOT_READERS] of them rather than two, which is what
     * finally made the collision likely rather than lucky.
     *
     * No name in this file may contain a hyphen. A gtest-style filter reads `-`
     * as its negation separator, so `--ktest_filter='*one-shot*'` runs **zero**
     * tests and exits 0 — which is how the earlier shape was recorded as
     * catching something.
     */
    @Test
    fun `an armed throw is taken exactly as many times as it is armed`() {
        val fake = FakeNativeSocket().apply { defaultRead = ReadResult.WouldBlock }
        val shared = Contention(KIND_ONE_SHOT, socket = fake)
        var armed = 0

        contendWith(shared, peers = ONE_SHOT_READERS) {
            awaitPeer(shared.started, parties = ONE_SHOT_READERS + 1)
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

    /**
     * Scripting one fd while another thread walks the same maps.
     *
     * This is the shape the sweep missed. The map-based `enqueue*` on the ops
     * fake insert into the very maps `assertAllConsumed` walks and the guarded
     * syscalls dequeue from, and they were left outside the lock because the
     * sweep matched on `deque.addAll(...)` and these read
     * `map.getOrPut(fd) { … }.addAll(...)`. Nothing in this file exercised them,
     * so nothing failed — measured: with those five restored to unguarded, all
     * four other tests here pass 20 of 20 on both hosts.
     *
     * Measured with those five restored to unguarded: this fails 20 of 20 on
     * both hosts, taking the process down with a `ConcurrentModificationException`
     * out of the walk.
     *
     * What it does not do is make the class KDoc's "every member" true by test.
     * Of roughly forty members the suite drives four — `read`, `setNonBlocking`,
     * one map-based `enqueue*`, `assertAllConsumed` — and the rest still rest on
     * review. A test per member is the only thing that would change that, and a
     * table of them would go stale against a fake that grows.
     */
    @Test
    fun `scripting one fd is safe while another thread walks the scripts`() {
        val fake = FakeNativeSocketOps()
        val shared = Contention(KIND_SCRIPT_WALK, ops = fake)

        contendWith(shared) {
            awaitPeer(shared.started)
            // A new fd each time, because that is what makes the insert
            // structural. Scripting the same fd repeatedly only appends to a
            // deque the map already holds, so the map itself never changes and
            // a walk over it sees nothing — measured: the same test against the
            // unguarded insert passed 20 of 20 that way.
            repeat(SCRIPT_WALK_INSERTS) { i ->
                fake.enqueueLocalAddress(i, InetSocketAddress(Host.Ip(IpAddress.V4.LOOPBACK), 0))
            }
            shared.stopped.value = 1
        }

        // The walk cannot be asserted against a number — the peer is consuming
        // while this thread inserts. What it must not do is see a map mid-insert,
        // which is a `ConcurrentModificationException` out of `assertAllConsumed`
        // and takes the process with it.
        assertEquals(0, shared.tally.value, "the walk must never fail on a map being written")
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
private const val KIND_SCRIPT_WALK = 4

private const val FD = 3

/**
 * High enough that an unguarded counter loses increments every run, low enough
 * that all four tests together stay well inside a second.
 */
private const val CALLS_PER_THREAD = 20_000

/** How many times the one-shot test re-arms while the readers hammer. */
private const val ONE_SHOT_ROUNDS = 200

/**
 * How many readers contend for each arming. Two was not enough on macOS — see
 * the test's own KDoc for what that cost.
 */
private const val ONE_SHOT_READERS = 4

/**
 * How many distinct fds the walk test scripts. Far fewer than the other tests
 * drive, because each insert grows the map the peer is walking, so the work is
 * quadratic — and the walker runs until told to stop rather than a fixed count,
 * which is what bounds it.
 */
private const val SCRIPT_WALK_INSERTS = 2_000

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

/** The spawned thread's half, dispatched on [Contention.kind]. */
@OptIn(ExperimentalForeignApi::class)
private fun runPeerHalf(shared: Contention) {
    awaitPeer(shared.started, if (shared.kind == KIND_ONE_SHOT) ONE_SHOT_READERS + 1 else 2)
    when (shared.kind) {
        KIND_COUNT -> shared.socket!!.readRepeatedly()
        KIND_SCRIPT -> shared.tally.addAndGet(shared.socket!!.readRepeatedly())
        // Allocating once and catching directly, because the window this races
        // is three instructions wide and a `memScoped` per iteration is orders
        // of magnitude longer than that.
        KIND_ONE_SHOT -> memScoped {
            val byte = alloc<ByteVar>()
            val socket = shared.socket!!
            while (shared.stopped.value == 0) {
                try {
                    socket.read(FD, byte.ptr, 1)
                } catch (expected: IllegalStateException) {
                    check(expected.message == "scripted") { "unexpected throw: $expected" }
                    shared.tally.incrementAndGet()
                }
            }
        }
        KIND_OPS -> repeat(CALLS_PER_THREAD) { shared.ops!!.setNonBlocking(FD) }
        KIND_SCRIPT_WALK -> while (shared.stopped.value == 0) {
            // `assertAllConsumed` throws by design while queues are non-empty,
            // which they are throughout. Only a walk that fails on the map's
            // own structure counts here.
            try {
                shared.ops!!.assertAllConsumed()
            } catch (expected: IllegalStateException) {
                check(expected.message?.startsWith("unconsumed scripted responses") == true) {
                    "unexpected failure from the walk: $expected"
                }
            }
        }
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
    val threads = List(peers) { arena.alloc<pthread_tVar>() }
    var spawned = 0
    try {
        while (spawned < peers) {
            val rc = pthread_create(
                threads[spawned].ptr,
                null,
                staticCFunction { arg ->
                    runPeerHalf(arg!!.asStableRef<Contention>().get())
                    null
                },
                ref.asCPointer(),
            )
            check(rc == 0) { "pthread_create failed: rc=$rc" }
            spawned++
        }
        hereSide()
    } finally {
        // Whatever went wrong, the threads that exist must be told to stop and
        // joined before the `StableRef` they hold is disposed. Only the
        // one-shot readers loop on `stopped`; the others are bounded and end on
        // their own. Without this, the bounded spin that turns a hang into a
        // failure turns it into readers spinning on freed state instead, and a
        // partial spawn leaves the same, since peers already running outlive
        // the `check` that stopped the loop.
        shared.stopped.value = 1
        repeat(spawned) { i -> pthread_join(threads[i].ptr[0], null) }
        ref.dispose()
        arena.clear()
    }
}
