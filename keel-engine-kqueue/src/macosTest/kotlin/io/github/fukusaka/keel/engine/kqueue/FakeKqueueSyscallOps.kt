package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.native.posix.errnoMessage
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.pthread_self
import platform.posix.usleep
import kotlin.concurrent.Volatile

/**
 * In-memory [KqueueSyscallOps] that lets tests script the outcome of
 * each syscall and inspect the call sequence. Single-threaded by
 * default — only safe to drive from the test thread.
 *
 * Each "script" method enqueues a FIFO outcome consumed by the
 * corresponding syscall call. Defaults (when the queue is empty) are
 * the happy path: `0` errno and a synthetic fd counter.
 *
 * **Cross-thread funnel testing (live mode).** The I/O ownership
 * invariant funnel test (`KqueueEventLoopFunnelSeamTest`) runs the real
 * `loop()` on a `start()`-spawned EventLoop pthread while the test
 * thread issues `registerCallback` cross-thread. For that one scenario:
 *
 * - [liveMode] makes the empty-default `waitEvents` path `usleep` briefly
 *   instead of returning `0` immediately, so the spawned EventLoop thread
 *   polls (drains dispatched tasks each iteration) without pegging a CPU.
 * - [lastAddFilterThreadId] captures the thread id (raw `pthread_self()`
 *   pointer as a [Long]) on which the most recent `addReadFilter` /
 *   `addWriteFilter` for [watchedFd] executed, so the test can assert the
 *   syscall ran on the EventLoop thread (cross-thread funnel) or the
 *   caller thread (inline fast path). It is `@Volatile` because it is
 *   written on the EventLoop thread and read on the test thread — the
 *   only cross-thread access this fake supports.
 */
@OptIn(ExperimentalForeignApi::class)
internal class FakeKqueueSyscallOps(
    private val initialFakeFd: Int = 1000,
) : KqueueSyscallOps {

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
     * Only `addReadFilter` / `addWriteFilter` calls for this fd update
     * [lastAddFilterThreadId]. Default `-1` captures nothing. The funnel
     * test sets it to the fd under test so the construction-time wakeup-fd
     * `EV_ADD` (which runs on the constructing thread) does not pollute
     * the captured thread id.
     */
    @Volatile
    var watchedFd: Int = -1

    /**
     * Identity (raw `pthread_self()` pointer as a [Long]) of the thread on
     * which the most recent `addReadFilter` / `addWriteFilter` for
     * [watchedFd] ran, or `0` if none yet. `@Volatile` for cross-thread
     * read by the funnel test (written on the EventLoop thread). A [Long]
     * is used rather than a `pthread_t` so the value survives `@Volatile`
     * storage without CPointer wrapping ambiguity; compare with
     * [currentThreadId] to verify funnel routing.
     */
    @Volatile
    var lastAddFilterThreadId: Long = 0L
        private set

    // --- kqueueCreate ---

    private val kqueueCreateResults = ArrayDeque<Int>()
    private var nextFakeFd: Int = initialFakeFd

    fun scriptKqueueCreateFd(fd: Int) {
        kqueueCreateResults.addLast(fd)
    }

    fun scriptKqueueCreateFailure(errno: Int) {
        require(errno > 0) { "errno must be positive, got $errno" }
        kqueueCreateResults.addLast(-errno)
    }

    override fun kqueueCreate(): Int =
        if (kqueueCreateResults.isEmpty()) nextFakeFd++ else kqueueCreateResults.removeFirst()

    // --- makePipe ---

    private sealed interface PipeResult {
        data class Ok(val readFd: Int, val writeFd: Int) : PipeResult
        data class Failed(val errno: Int) : PipeResult
    }
    private val makePipeResults = ArrayDeque<PipeResult>()

    fun scriptMakePipeFds(readFd: Int, writeFd: Int) {
        makePipeResults.addLast(PipeResult.Ok(readFd, writeFd))
    }

    fun scriptMakePipeFailure(errno: Int) {
        require(errno > 0)
        makePipeResults.addLast(PipeResult.Failed(errno))
    }

    var setNonBlockingCalls: Int = 0
        private set

    private val setNonBlockingResults = ArrayDeque<Int>()

    /**
     * Makes the next [setNonBlocking] call throw, the way the production impl
     * does when `fcntl` fails — its contract is to fail-fast rather than
     * report, so this is the only outcome a caller can be asked to handle.
     *
     * FIFO like the other scripts: enqueue one success ([scriptSetNonBlockingSuccess])
     * per call that should get through first.
     */
    fun scriptSetNonBlockingFailure(errno: Int) {
        require(errno > 0) { "errno must be positive, got $errno" }
        setNonBlockingResults.addLast(errno)
    }

    /** Enqueues one call that succeeds, to position a scripted failure after it. */
    fun scriptSetNonBlockingSuccess() {
        setNonBlockingResults.addLast(0)
    }

    override fun setNonBlocking(fd: Int) {
        setNonBlockingCalls++
        val err = if (setNonBlockingResults.isEmpty()) 0 else setNonBlockingResults.removeFirst()
        if (err != 0) error("fcntl(F_SETFL, O_NONBLOCK, fd=$fd) failed: ${errnoMessage(err)}")
    }

    override fun makePipe(fds: IntArray): Int {
        val r = if (makePipeResults.isEmpty()) {
            PipeResult.Ok(nextFakeFd++, nextFakeFd++)
        } else {
            makePipeResults.removeFirst()
        }
        return when (r) {
            is PipeResult.Ok -> {
                fds[0] = r.readFd
                fds[1] = r.writeFd
                0
            }
            is PipeResult.Failed -> r.errno
        }
    }

    // --- addReadFilter / addWriteFilter / deleteReadFilter / deleteWriteFilter ---

    enum class FilterKind { READ, WRITE }
    data class AddFilterCall(val kqFd: Int, val fd: Int, val filter: FilterKind)
    data class DeleteFilterCall(val kqFd: Int, val fd: Int, val filter: FilterKind)

    val addFilterCalls: MutableList<AddFilterCall> = mutableListOf()
    private val addFilterResults = ArrayDeque<Int>()

    /** Scripts the next `addReadFilter` / `addWriteFilter` call to return [errno] (0 = success). */
    fun scriptAddFilterResult(errno: Int) {
        require(errno >= 0)
        addFilterResults.addLast(errno)
    }

    override fun addReadFilter(kqFd: Int, fd: Int): Int {
        if (fd == watchedFd) lastAddFilterThreadId = currentThreadId()
        addFilterCalls.add(AddFilterCall(kqFd, fd, FilterKind.READ))
        return if (addFilterResults.isEmpty()) 0 else addFilterResults.removeFirst()
    }

    override fun addWriteFilter(kqFd: Int, fd: Int): Int {
        if (fd == watchedFd) lastAddFilterThreadId = currentThreadId()
        addFilterCalls.add(AddFilterCall(kqFd, fd, FilterKind.WRITE))
        return if (addFilterResults.isEmpty()) 0 else addFilterResults.removeFirst()
    }

    val deleteFilterCalls: MutableList<DeleteFilterCall> = mutableListOf()
    private val deleteFilterResults = ArrayDeque<Int>()

    /** Scripts the next `deleteReadFilter` / `deleteWriteFilter` call to return [errno] (0 = success). */
    fun scriptDeleteFilterResult(errno: Int) {
        require(errno >= 0)
        deleteFilterResults.addLast(errno)
    }

    override fun deleteReadFilter(kqFd: Int, fd: Int): Int {
        deleteFilterCalls.add(DeleteFilterCall(kqFd, fd, FilterKind.READ))
        return if (deleteFilterResults.isEmpty()) 0 else deleteFilterResults.removeFirst()
    }

    override fun deleteWriteFilter(kqFd: Int, fd: Int): Int {
        deleteFilterCalls.add(DeleteFilterCall(kqFd, fd, FilterKind.WRITE))
        return if (deleteFilterResults.isEmpty()) 0 else deleteFilterResults.removeFirst()
    }

    // --- waitEvents ---

    sealed interface ScriptedWait {
        /** Wait succeeds, fills [events] (fd, filter, flags) into the caller's `eventsOut`. */
        data class Ok(val events: List<Triple<Int, Int, Int>>) : ScriptedWait

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

    fun scriptWaitOk(vararg events: Triple<Int, Int, Int>) {
        waitResults.addLast(ScriptedWait.Ok(events.toList()))
    }

    fun scriptWaitFailure(errno: Int) {
        require(errno > 0)
        waitResults.addLast(ScriptedWait.Failed(errno))
    }

    override fun waitEvents(kqFd: Int, eventsOut: Array<KqEvent>, timeoutMillis: Long): Int {
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
                    eventsOut[i].filter = r.events[i].second
                    eventsOut[i].flags = r.events[i].third
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

    override fun wakeupWrite(writeFd: Int, scratch: ByteArray): Int {
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

    override fun wakeupDrain(readFd: Int, scratch: ByteArray): Int {
        wakeupDrainCalls++
        return if (wakeupDrainResults.isEmpty()) 0 else wakeupDrainResults.removeFirst()
    }

    companion object {
        /** Live-mode poll-sleep between empty `waitEvents` returns (~200µs). */
        private const val POLL_SLEEP_MICROS: UInt = 200u

        /**
         * Raw `pthread_self()` pointer of the calling thread as a [Long],
         * a stable per-thread identity. Used by the funnel test to compare
         * the caller thread against [lastAddFilterThreadId].
         */
        fun currentThreadId(): Long = pthread_self()?.rawValue?.toLong() ?: 0L
    }
}
