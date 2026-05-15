package io.github.fukusaka.keel.engine.iouring

/**
 * In-memory [IoUringSyscallOps] that lets tests script the outcome of
 * each non-`io_uring` kernel syscall used by [IoUringEventLoop] and
 * inspect the call sequence. Single-threaded — only safe to drive from
 * the test thread.
 *
 * Each "script" method enqueues a FIFO outcome consumed by the
 * corresponding syscall call. Defaults (when the queue is empty) are
 * the happy path: a synthetic fd counter for [eventfdCreate] and `0`
 * (success) for [eventfdWakeupWrite]. Counterpart of
 * [io.github.fukusaka.keel.engine.epoll.FakeEpollSyscallOps] on the
 * io_uring side.
 */
internal class FakeIoUringSyscallOps(
    private val initialFakeFd: Int = 2000,
) : IoUringSyscallOps {

    // --- eventfdCreate ---

    private val eventfdCreateResults = ArrayDeque<Int>()
    private var nextFakeFd: Int = initialFakeFd

    /** Tracks every [eventfdCreate] invocation, regardless of scripted vs. default. */
    var eventfdCreateCalls: Int = 0
        private set

    /** Scripts the next [eventfdCreate] call to return [fd]. */
    fun scriptEventfdCreateFd(fd: Int) {
        eventfdCreateResults.addLast(fd)
    }

    /** Scripts the next [eventfdCreate] call to fail with [errno] (encoded as `-errno`). */
    fun scriptEventfdCreateFailure(errno: Int) {
        require(errno > 0) { "errno must be positive, got $errno" }
        eventfdCreateResults.addLast(-errno)
    }

    override fun eventfdCreate(): Int {
        eventfdCreateCalls++
        return if (eventfdCreateResults.isEmpty()) nextFakeFd++ else eventfdCreateResults.removeFirst()
    }

    // --- eventfdWakeupWrite ---

    private val eventfdWakeupWriteResults = ArrayDeque<Int>()

    /** Tracks every [eventfdWakeupWrite] invocation. */
    var eventfdWakeupWriteCalls: Int = 0
        private set

    /** Records the `eventfd` argument of every [eventfdWakeupWrite] call (in order). */
    val eventfdWakeupWriteArgs: MutableList<Int> = mutableListOf()

    /**
     * Scripts the next [eventfdWakeupWrite] call to return [errno].
     * Use `0` for success, a positive errno (e.g. `EAGAIN`, `EBADF`) for failure.
     */
    fun scriptEventfdWakeupWriteResult(errno: Int) {
        require(errno >= 0) { "errno must be non-negative, got $errno" }
        eventfdWakeupWriteResults.addLast(errno)
    }

    override fun eventfdWakeupWrite(eventfd: Int): Int {
        eventfdWakeupWriteCalls++
        eventfdWakeupWriteArgs.add(eventfd)
        return if (eventfdWakeupWriteResults.isEmpty()) 0 else eventfdWakeupWriteResults.removeFirst()
    }
}
