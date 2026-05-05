package io.github.fukusaka.keel.engine.kqueue

/**
 * In-memory [KqueueSyscallOps] that lets tests script the outcome of
 * each syscall and inspect the call sequence. Single-threaded — only
 * safe to drive from the test thread.
 *
 * Each "script" method enqueues a FIFO outcome consumed by the
 * corresponding syscall call. Defaults (when the queue is empty) are
 * the happy path: `0` errno and a synthetic fd counter.
 */
internal class FakeKqueueSyscallOps(
    private val initialFakeFd: Int = 1000,
) : KqueueSyscallOps {

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

    override fun setNonBlocking(fd: Int) {
        setNonBlockingCalls++
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
        addFilterCalls.add(AddFilterCall(kqFd, fd, FilterKind.READ))
        return if (addFilterResults.isEmpty()) 0 else addFilterResults.removeFirst()
    }

    override fun addWriteFilter(kqFd: Int, fd: Int): Int {
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

    fun scriptWaitOk(vararg events: Triple<Int, Int, Int>) {
        waitResults.addLast(ScriptedWait.Ok(events.toList()))
    }

    fun scriptWaitFailure(errno: Int) {
        require(errno > 0)
        waitResults.addLast(ScriptedWait.Failed(errno))
    }

    override fun waitEvents(kqFd: Int, eventsOut: Array<KqEvent>, timeoutNanos: Long): Int {
        waitCalls++
        val r = if (waitResults.isEmpty()) return 0 else waitResults.removeFirst()
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
}
