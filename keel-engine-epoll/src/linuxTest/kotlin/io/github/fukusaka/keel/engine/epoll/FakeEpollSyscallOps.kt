package io.github.fukusaka.keel.engine.epoll

/**
 * In-memory [EpollSyscallOps] that lets tests script the outcome of
 * each syscall and inspect the call sequence. Single-threaded — only
 * safe to drive from the test thread.
 *
 * Each "script" method enqueues a FIFO outcome consumed by the
 * corresponding syscall call. Defaults (when the queue is empty) are
 * the happy path: `0` errno and a synthetic fd counter.
 */
internal class FakeEpollSyscallOps(
    private val initialFakeFd: Int = 1000,
) : EpollSyscallOps {

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

    enum class CtlOp { ADD, MOD }
    data class CtlCall(val op: CtlOp, val epFd: Int, val fd: Int, val events: Int)

    val ctlCalls: MutableList<CtlCall> = mutableListOf()
    private val addResults = ArrayDeque<Int>()
    private val modResults = ArrayDeque<Int>()

    fun scriptAddResult(errno: Int) {
        require(errno >= 0)
        addResults.addLast(errno)
    }

    fun scriptModResult(errno: Int) {
        require(errno >= 0)
        modResults.addLast(errno)
    }

    override fun epollAdd(epFd: Int, fd: Int, events: Int): Int {
        ctlCalls.add(CtlCall(CtlOp.ADD, epFd, fd, events))
        return if (addResults.isEmpty()) 0 else addResults.removeFirst()
    }

    override fun epollMod(epFd: Int, fd: Int, events: Int): Int {
        ctlCalls.add(CtlCall(CtlOp.MOD, epFd, fd, events))
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

    fun scriptWaitOk(vararg events: Pair<Int, Int>) {
        waitResults.addLast(ScriptedWait.Ok(events.toList()))
    }

    fun scriptWaitFailure(errno: Int) {
        require(errno > 0)
        waitResults.addLast(ScriptedWait.Failed(errno))
    }

    override fun waitEvents(epFd: Int, eventsOut: Array<EpEvent>, timeoutMs: Int): Int {
        waitCalls++
        val r = if (waitResults.isEmpty()) return 0 else waitResults.removeFirst()
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
}
