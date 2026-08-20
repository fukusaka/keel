package io.github.fukusaka.keel.engine.epoll

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.pthread_self
import platform.posix.pthread_t
import platform.posix.usleep
import kotlin.concurrent.Volatile

/**
 * In-memory [EpollSyscallOps] that lets tests script the outcome of
 * each syscall and inspect the call sequence. Single-threaded by
 * default — only safe to drive from the test thread.
 *
 * Each "script" method enqueues a FIFO outcome consumed by the
 * corresponding syscall call. Defaults (when the queue is empty) are
 * the happy path: `0` errno and a synthetic fd counter.
 *
 * **Cross-thread funnel testing (live mode).** The I/O ownership
 * invariant funnel test (`EpollEventLoopFunnelSeamTest`) runs the real
 * `loop()` on a `start()`-spawned EventLoop pthread while the test
 * thread issues `registerCallback` cross-thread. For that one scenario:
 *
 * - [liveMode] makes the empty-default `waitEvents` path `usleep` briefly
 *   instead of returning `0` immediately, so the spawned EventLoop thread
 *   polls (drains dispatched tasks each iteration) without pegging a CPU.
 * - [lastAddInterestThread] captures the pthread on which the most recent
 *   `epollAdd` / `epollMod` / `epollDel` for [watchedFd] ran, so the test can assert
 *   the syscall ran on the EventLoop thread (cross-thread funnel) or the
 *   caller thread (inline fast path), via `pthread_equal`. It is
 *   `@Volatile` because it is written on the EventLoop thread and read on
 *   the test thread — the only cross-thread access this fake supports.
 */
@OptIn(ExperimentalForeignApi::class)
internal class FakeEpollSyscallOps(
    private val initialFakeFd: Int = 1000,
) : EpollSyscallOps {

    /**
     * When `true`, the empty-default [waitEvents] path sleeps ~200µs
     * before returning `0` so a real `loop()` running on a spawned
     * EventLoop thread polls instead of busy-spinning. Default `false`
     * preserves the immediate-return behaviour the existing
     * `loop()`-driving seam tests rely on (they script `waitEvents`
     * failures to terminate and never hit the empty-default spin).
     */
    @Volatile
    var liveMode: Boolean = false

    /**
     * Only `epollAdd` / `epollMod` / `epollDel` calls for this fd update
     * [lastAddInterestThread]. Default `-1` captures nothing. The funnel
     * test sets it to the fd under test so the construction-time
     * wakeup-eventfd `EPOLL_CTL_ADD` (which runs on the constructing
     * thread) does not pollute the captured thread.
     */
    @Volatile
    var watchedFd: Int = -1

    /**
     * The pthread on which the most recent `epollAdd` / `epollMod` / `epollDel` for
     * [watchedFd] ran, or `null` if none yet. `@Volatile` for cross-thread
     * read by the funnel test (written on the EventLoop thread). Compare
     * with `pthread_self()` via `pthread_equal` to verify funnel routing —
     * the same idiom `EpollEventLoop.inEventLoop` uses.
     */
    @Volatile
    var lastAddInterestThread: pthread_t? = null

    /**
     * Number of recorded `ctlCalls` at the moment [lastAddInterestThread] was
     * last published, for a reader that must wait for a specific call to have
     * landed rather than merely for the first one.
     */
    @Volatile
    var ctlCallCount: Int = 0
        private set

    // --- epollCreate ---

    private val epollCreateResults = ArrayDeque<Int>()
    private var nextFakeFd: Int = initialFakeFd

    fun scriptEpollCreateFd(fd: Int) {
        epollCreateResults.addLast(fd)
    }

    fun scriptEpollCreateFailure(errno: Int) {
        require(errno > 0)
        epollCreateResults.addLast(-errno)
    }

    override fun epollCreate(): Int =
        if (epollCreateResults.isEmpty()) nextFakeFd++ else epollCreateResults.removeFirst()

    // --- eventfdCreate ---

    private val eventfdCreateResults = ArrayDeque<Int>()

    fun scriptEventfdCreateFd(fd: Int) {
        eventfdCreateResults.addLast(fd)
    }

    fun scriptEventfdCreateFailure(errno: Int) {
        require(errno > 0)
        eventfdCreateResults.addLast(-errno)
    }

    override fun eventfdCreate(): Int =
        if (eventfdCreateResults.isEmpty()) nextFakeFd++ else eventfdCreateResults.removeFirst()

    // --- epollAdd / epollMod ---

    enum class CtlOp { ADD, MOD, DEL }
    data class CtlCall(val op: CtlOp, val epFd: Int, val fd: Int, val events: Int)

    val ctlCalls: MutableList<CtlCall> = mutableListOf()
    private val addResults = ArrayDeque<Int>()
    private val modResults = ArrayDeque<Int>()
    private val delResults = ArrayDeque<Int>()

    fun scriptAddResult(errno: Int) {
        require(errno >= 0)
        addResults.addLast(errno)
    }

    fun scriptModResult(errno: Int) {
        require(errno >= 0)
        modResults.addLast(errno)
    }

    /** Scripts the next `epollDel` call to return [errno] (0 = success). */
    fun scriptDelResult(errno: Int) {
        require(errno >= 0)
        delResults.addLast(errno)
    }

    override fun epollAdd(epFd: Int, fd: Int, events: Int): Int {
        ctlCalls.add(CtlCall(CtlOp.ADD, epFd, fd, events))
        // Published after the append, not before: a reader that waits on this
        // volatile is waiting to see the call recorded, and the release edge
        // only covers writes that precede it.
        if (fd == watchedFd) {
            ctlCallCount = ctlCalls.size
            lastAddInterestThread = pthread_self()
        }
        return if (addResults.isEmpty()) 0 else addResults.removeFirst()
    }

    override fun epollDel(epFd: Int, fd: Int): Int {
        ctlCalls.add(CtlCall(CtlOp.DEL, epFd, fd, 0))
        if (fd == watchedFd) {
            ctlCallCount = ctlCalls.size
            lastAddInterestThread = pthread_self()
        }
        return if (delResults.isEmpty()) 0 else delResults.removeFirst()
    }

    override fun epollMod(epFd: Int, fd: Int, events: Int): Int {
        ctlCalls.add(CtlCall(CtlOp.MOD, epFd, fd, events))
        // Published after the append, not before: a reader that waits on this
        // volatile is waiting to see the call recorded, and the release edge
        // only covers writes that precede it.
        if (fd == watchedFd) {
            ctlCallCount = ctlCalls.size
            lastAddInterestThread = pthread_self()
        }
        return if (modResults.isEmpty()) 0 else modResults.removeFirst()
    }

    // --- waitEvents ---

    sealed interface ScriptedWait {
        /** Wait succeeds, fills [events] (fd, event-bitmask) into the caller's `eventsOut`. */
        data class Ok(val events: List<Pair<Int, Int>>) : ScriptedWait

        /** Wait returns -errno. */
        data class Failed(val errno: Int) : ScriptedWait
    }

    private val waitResults = ArrayDeque<ScriptedWait>()
    var waitCalls: Int = 0
        private set

    /**
     * Run at the top of each [waitEvents], before the scripted result is
     * consumed. For a test that needs the loop to leave its body the way a
     * stop request makes it leave — `close()` from inside the wait, so the
     * body's own condition is what ends it — rather than by never entering it.
     */
    var onWait: (() -> Unit)? = null

    fun scriptWaitOk(vararg events: Pair<Int, Int>) {
        waitResults.addLast(ScriptedWait.Ok(events.toList()))
    }

    fun scriptWaitFailure(errno: Int) {
        require(errno > 0)
        waitResults.addLast(ScriptedWait.Failed(errno))
    }

    override fun waitEvents(epFd: Int, eventsOut: Array<EpEvent>, timeoutMs: Int): Int {
        waitCalls++
        onWait?.invoke()
        if (waitResults.isEmpty()) {
            // Empty-default path. In live mode (funnel test), poll-sleep so
            // a real loop() on a spawned EventLoop thread drains dispatched
            // tasks each iteration without pegging a CPU.
            if (liveMode) usleep(POLL_SLEEP_MICROS)
            return 0
        }
        val r = waitResults.removeFirst()
        return when (r) {
            is ScriptedWait.Ok -> {
                for (i in r.events.indices) {
                    eventsOut[i].fd = r.events[i].first
                    eventsOut[i].events = r.events[i].second
                }
                r.events.size
            }
            is ScriptedWait.Failed -> -r.errno
        }
    }

    // --- wakeup ---

    var wakeupWriteCalls: Int = 0
        private set
    private val wakeupWriteResults = ArrayDeque<Int>()

    fun scriptWakeupWriteResult(errno: Int) {
        require(errno >= 0)
        wakeupWriteResults.addLast(errno)
    }

    override fun eventfdWakeupWrite(eventfd: Int): Int {
        wakeupWriteCalls++
        return if (wakeupWriteResults.isEmpty()) 0 else wakeupWriteResults.removeFirst()
    }

    var wakeupDrainCalls: Int = 0
        private set
    private val wakeupDrainResults = ArrayDeque<Int>()

    fun scriptWakeupDrainResult(errno: Int) {
        require(errno >= 0)
        wakeupDrainResults.addLast(errno)
    }

    override fun eventfdWakeupDrain(eventfd: Int): Int {
        wakeupDrainCalls++
        return if (wakeupDrainResults.isEmpty()) 0 else wakeupDrainResults.removeFirst()
    }

    private companion object {
        /** Live-mode poll-sleep between empty `waitEvents` returns (~200µs). */
        const val POLL_SLEEP_MICROS: UInt = 200u
    }
}
