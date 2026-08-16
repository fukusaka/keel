package io.github.fukusaka.keel.native.posix

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar

/**
 * Test-only in-memory [NativeSocket] implementation.
 *
 * Unit tests that drive engine code through specific `errno` branches
 * (read → `Eof`, connect → `Failed(ECONNREFUSED)`, send → spurious
 * `WouldBlock`, etc.) previously required a real kernel + loopback
 * socket to reproduce, which is why most errno branches were only
 * covered by integration-style stress tests such as
 * `IoUringPipelinedServerTest`. The [NativeSocket] seam introduced in
 * PR #323 lets tests inject this fake instead, making every branch
 * cheaply and deterministically exercisable.
 *
 * ## Model
 *
 * For each syscall the fake holds a per-[fd] FIFO queue of scripted
 * responses. Each call pops the next response for that fd; when the
 * queue is empty the matching `default*` property is returned. A test
 * therefore enqueues the exact sequence it wants the system under
 * test to observe:
 *
 * ```kotlin
 * val fake = FakeNativeSocket()
 * fake.enqueueRead(fd = 3, ReadResult.Bytes(4), ReadResult.Eof)
 * // first call returns Bytes(4), second returns Eof, third falls
 * // back to defaultRead (WouldBlock).
 * ```
 *
 * `close(fd)` is tracked separately in [closedFds] (ordered list —
 * enables double-close detection and teardown-order assertions).
 *
 * ## Thread safety
 *
 * Every member is safe to call from any thread: the scripted state sits behind
 * a [FakeSocketLock], so a counter read cannot tear and a dequeue cannot race
 * an [assertAllConsumed] walking the same map.
 *
 * That is not the same as knowing *when* to read. The lock serialises the
 * fake's own state; it does not order the code under test, so a counter read
 * still answers "how many calls had happened by the time you looked". A seam
 * test that wants a definite answer dispatches a marker through the loop's FIFO
 * and awaits it — the fixtures' `awaitLoopDrained` — and then reads.
 *
 * The previous contract said "single-threaded only; wrap in `synchronized` at
 * the call site". No caller could honour it: the other caller is the engine,
 * issuing syscalls from its own loop thread, and no test can wrap that.
 *
 * ## What the fake does NOT do
 *
 * - **No buffer I/O**: [read] / [write] / [writev] / [send] do not
 *   touch the caller's [CPointer] buffer. Tests that need payload
 *   verification should either assert on `ReadResult.Bytes(n)` and
 *   not inspect buffer contents, or extend the fake via composition
 *   (keep scope narrow here).
 * - **No fd allocation**: `accept` returns fds the test enqueued, so
 *   the test controls numbering. Real POSIX would return the lowest
 *   unused fd.
 * - **No kernel side effects**: `shutdown` / `close` only update the
 *   fake's bookkeeping — no actual socket is touched.
 * - **No argument capture**: `connect(addr, addrLen)`,
 *   `shutdown(how)`, `send(flags)`, `writev(regions)` and the
 *   `length` parameter are consumed but NOT recorded. Tests cannot
 *   assert that the system under test built the correct sockaddr or
 *   picked the correct `how`. If an assertion like that is needed,
 *   wrap the fake via composition and override the relevant method
 *   to snapshot the argument — keeping the base class argument-less
 *   avoids dragging capture state into every test that doesn't
 *   need it.
 */
@OptIn(ExperimentalForeignApi::class)
public class FakeNativeSocket : NativeSocket {

    private val lock = FakeSocketLock()

    // --- Scripted response queues ---

    private val readQueue = mutableMapOf<Int, ArrayDeque<ReadResult>>()
    private val writeQueue = mutableMapOf<Int, ArrayDeque<WriteResult>>()
    private val writevQueue = mutableMapOf<Int, ArrayDeque<WriteResult>>()
    private val sendQueue = mutableMapOf<Int, ArrayDeque<WriteResult>>()
    private val acceptQueue = mutableMapOf<Int, ArrayDeque<AcceptResult>>()
    private val connectQueue = mutableMapOf<Int, ArrayDeque<ConnectResult>>()
    private val shutdownQueue = mutableMapOf<Int, ArrayDeque<ShutdownResult>>()
    private val closeQueue = mutableMapOf<Int, ArrayDeque<CloseResult>>()

    // --- Defaults (consulted when queue is empty) ---

    /** Returned by [read] when no response is queued for the fd. */
    public var defaultRead: ReadResult
        get() = lock.withLock { _defaultRead }
        set(value) = lock.withLock { _defaultRead = value }
    private var _defaultRead: ReadResult = ReadResult.WouldBlock

    /** Returned by [write] / [writev] / [send] when no response is queued. */
    public var defaultWrite: WriteResult
        get() = lock.withLock { _defaultWrite }
        set(value) = lock.withLock { _defaultWrite = value }
    private var _defaultWrite: WriteResult = WriteResult.WouldBlock

    /** Returned by [accept] when no response is queued for the server fd. */
    public var defaultAccept: AcceptResult
        get() = lock.withLock { _defaultAccept }
        set(value) = lock.withLock { _defaultAccept = value }
    private var _defaultAccept: AcceptResult = AcceptResult.WouldBlock

    /** Returned by [connect] when no response is queued for the fd. */
    public var defaultConnect: ConnectResult
        get() = lock.withLock { _defaultConnect }
        set(value) = lock.withLock { _defaultConnect = value }
    private var _defaultConnect: ConnectResult = ConnectResult.InProgress

    /** Returned by [shutdown] when no response is queued for the fd. */
    public var defaultShutdown: ShutdownResult
        get() = lock.withLock { _defaultShutdown }
        set(value) = lock.withLock { _defaultShutdown = value }
    private var _defaultShutdown: ShutdownResult = ShutdownResult.Ok

    /** Returned by [close] when no response is queued for the fd. */
    public var defaultClose: CloseResult
        get() = lock.withLock { _defaultClose }
        set(value) = lock.withLock { _defaultClose = value }
    private var _defaultClose: CloseResult = CloseResult.Ok

    // --- Call tracking ---

    /**
     * Number of times each syscall was invoked, summed across all fds.
     *
     * Read under the lock, so the value is whole; it is still a snapshot of a
     * loop that may be running. See the class KDoc on when to look.
     */
    public val readCalls: Int get() = lock.withLock { _readCalls }
    public val writeCalls: Int get() = lock.withLock { _writeCalls }
    public val writevCalls: Int get() = lock.withLock { _writevCalls }
    public val sendCalls: Int get() = lock.withLock { _sendCalls }
    public val acceptCalls: Int get() = lock.withLock { _acceptCalls }
    public val connectCalls: Int get() = lock.withLock { _connectCalls }
    public val shutdownCalls: Int get() = lock.withLock { _shutdownCalls }
    public val closeCalls: Int get() = lock.withLock { _closeCalls }

    private var _readCalls: Int = 0
    private var _writeCalls: Int = 0
    private var _writevCalls: Int = 0
    private var _sendCalls: Int = 0
    private var _acceptCalls: Int = 0
    private var _connectCalls: Int = 0
    private var _shutdownCalls: Int = 0
    private var _closeCalls: Int = 0

    private val _closedFds = mutableListOf<Int>()

    /**
     * Ordered list of fds passed to [close], in invocation order.
     * Duplicates are preserved — a double-close shows up as two entries
     * so tests can assert idempotence or detect leaks. See
     * [assertNoDoubleClose] for the common assertion.
     */
    public val closedFds: List<Int> get() = lock.withLock { _closedFds.toList() }

    // --- NativeSocket impl ---

    /**
     * Thrown by the next [read] call, then cleared.
     *
     * Stands in for the read path failing between the buffer allocation and
     * the hand-off to the transport's reader — the window in which a throw
     * leaks a pooled buffer no one else can reach. One-shot so a test can
     * assert what the connection does afterwards.
     */
    public var readThrowsOnce: Throwable?
        get() = lock.withLock { _readThrowsOnce }
        set(value) = lock.withLock { _readThrowsOnce = value }
    private var _readThrowsOnce: Throwable? = null

    /**
     * Thrown by the next [accept] call, then cleared.
     *
     * Stands in for the accept loop failing somewhere other than the per-socket
     * setup it guards individually, which is what decides whether the listener
     * is left armed. One-shot for the same reason as [readThrowsOnce].
     */
    public var acceptThrowsOnce: Throwable?
        get() = lock.withLock { _acceptThrowsOnce }
        set(value) = lock.withLock { _acceptThrowsOnce = value }
    private var _acceptThrowsOnce: Throwable? = null

    override fun read(fd: Int, buf: CPointer<ByteVar>, length: Int): ReadResult {
        // The count, the one-shot and the dequeue are one step: a reader that
        // catches the fake between them sees a call counted but not answered,
        // or a one-shot that two threads both took.
        return lock.withLock {
            _readCalls++
            _readThrowsOnce?.let {
                _readThrowsOnce = null
                throw it
            }
            readQueue[fd]?.removeFirstOrNull() ?: _defaultRead
        }
    }

    /**
     * Thrown by the next [write], [writev] or [send] call, then cleared.
     *
     * The seam for a flush that fails rather than returning a [WriteResult]. A
     * scripted `WriteResult.Failed` exercises the engine's own error branch;
     * this exercises the paths that assume flushing cannot throw at all —
     * teardown drains one deferred flush before releasing, and had no answer
     * for that call not returning.
     *
     * **All three write syscalls, not just the two the POSIX transports use.**
     * `flushSingle` reaches `write` on epoll and kqueue and `send` on io_uring,
     * with `writev` shared by the gather paths. A seam wired to only some of
     * them fails silently on an engine that uses the others: the fake returns
     * [defaultWrite], the drain succeeds, and a test written to assert what a
     * failing drain costs goes green against a build that never fixed it.
     */
    public var flushThrowsOnce: Throwable?
        get() = lock.withLock { _flushThrowsOnce }
        set(value) = lock.withLock { _flushThrowsOnce = value }
    private var _flushThrowsOnce: Throwable? = null

    /**
     * The three write syscalls, which share a queue-per-fd, a default and one
     * one-shot throw. [count] runs inside the guarded region so the increment
     * cannot be observed apart from the answer it belongs to.
     */
    private inline fun flushing(queue: Map<Int, ArrayDeque<WriteResult>>, fd: Int, count: () -> Unit): WriteResult =
        lock.withLock {
            count()
            _flushThrowsOnce?.let {
                _flushThrowsOnce = null
                throw it
            }
            queue[fd]?.removeFirstOrNull() ?: _defaultWrite
        }

    override fun write(fd: Int, buf: CPointer<ByteVar>, length: Int): WriteResult =
        flushing(writeQueue, fd) { _writeCalls++ }

    override fun writev(
        fd: Int,
        bases: CPointer<CPointerVar<ByteVar>>,
        lens: CPointer<ULongVar>,
        count: Int,
    ): WriteResult {
        // The one precondition a real kernel answers with an unclassifiable
        // errno, so the seam answers it with a name instead: a caller that
        // outgrows the bound fails here, in a test, rather than losing a
        // whole queue in production.
        check(count <= IOV_MAX) { "writev() offered $count regions, over the $IOV_MAX the platform takes" }
        return flushing(writevQueue, fd) { _writevCalls++ }
    }

    override fun accept(serverFd: Int): AcceptResult {
        return lock.withLock {
            _acceptCalls++
            _acceptThrowsOnce?.let {
                _acceptThrowsOnce = null
                throw it
            }
            acceptQueue[serverFd]?.removeFirstOrNull() ?: _defaultAccept
        }
    }

    override fun connect(fd: Int, addr: CPointer<ByteVar>, addrLen: Int): ConnectResult =
        lock.withLock {
            _connectCalls++
            connectQueue[fd]?.removeFirstOrNull() ?: _defaultConnect
        }

    override fun send(fd: Int, buf: CPointer<ByteVar>, length: Int, flags: Int): WriteResult =
        flushing(sendQueue, fd) { _sendCalls++ }

    override fun shutdown(fd: Int, how: Int): ShutdownResult =
        lock.withLock {
            _shutdownCalls++
            shutdownQueue[fd]?.removeFirstOrNull() ?: _defaultShutdown
        }

    override fun close(fd: Int): CloseResult =
        lock.withLock {
            _closeCalls++
            _closedFds.add(fd)
            closeQueue[fd]?.removeFirstOrNull() ?: _defaultClose
        }

    // --- Script setup ---

    /** Appends [results] to the scripted queue for `read(fd, ...)`. */
    public fun enqueueRead(fd: Int, vararg results: ReadResult) {
        lock.withLock { readQueue.getOrPut(fd) { ArrayDeque() }.addAll(results) }
    }

    /** Appends [results] to the scripted queue for `write(fd, ...)`. */
    public fun enqueueWrite(fd: Int, vararg results: WriteResult) {
        lock.withLock { writeQueue.getOrPut(fd) { ArrayDeque() }.addAll(results) }
    }

    /** Appends [results] to the scripted queue for `writev(fd, ...)`. */
    public fun enqueueWritev(fd: Int, vararg results: WriteResult) {
        lock.withLock { writevQueue.getOrPut(fd) { ArrayDeque() }.addAll(results) }
    }

    /** Appends [results] to the scripted queue for `send(fd, ...)`. */
    public fun enqueueSend(fd: Int, vararg results: WriteResult) {
        lock.withLock { sendQueue.getOrPut(fd) { ArrayDeque() }.addAll(results) }
    }

    /** Appends [results] to the scripted queue for `accept(serverFd)`. */
    public fun enqueueAccept(serverFd: Int, vararg results: AcceptResult) {
        lock.withLock { acceptQueue.getOrPut(serverFd) { ArrayDeque() }.addAll(results) }
    }

    /** Appends [results] to the scripted queue for `connect(fd, ...)`. */
    public fun enqueueConnect(fd: Int, vararg results: ConnectResult) {
        lock.withLock { connectQueue.getOrPut(fd) { ArrayDeque() }.addAll(results) }
    }

    /** Appends [results] to the scripted queue for `shutdown(fd, how)`. */
    public fun enqueueShutdown(fd: Int, vararg results: ShutdownResult) {
        lock.withLock { shutdownQueue.getOrPut(fd) { ArrayDeque() }.addAll(results) }
    }

    /** Appends [results] to the scripted queue for `close(fd)`. */
    public fun enqueueClose(fd: Int, vararg results: CloseResult) {
        lock.withLock { closeQueue.getOrPut(fd) { ArrayDeque() }.addAll(results) }
    }

    // --- Assertion helpers ---

    /**
     * Throws if every fd in [closedFds] is NOT unique. Useful as a
     * teardown-order sanity check. Quiet when close was never called.
     */
    public fun assertNoDoubleClose() {
        val closed = closedFds
        val seen = mutableSetOf<Int>()
        for (fd in closed) {
            check(seen.add(fd)) { "fd=$fd was closed more than once: $closed" }
        }
    }

    /**
     * Throws if any scripted response queue is non-empty. Call at the
     * end of a test to verify every queued response was consumed —
     * an unconsumed response usually means the system under test
     * short-circuited before reaching that branch.
     */
    public fun assertAllConsumed() {
        val leftovers = lock.withLock {
            buildList {
                fun report(name: String, queues: Map<Int, ArrayDeque<*>>) {
                    for ((fd, q) in queues) {
                        if (q.isNotEmpty()) add("$name(fd=$fd): ${q.size} remaining")
                    }
                }
                report("read", readQueue)
                report("write", writeQueue)
                report("writev", writevQueue)
                report("send", sendQueue)
                report("accept", acceptQueue)
                report("connect", connectQueue)
                report("shutdown", shutdownQueue)
                report("close", closeQueue)
            }
        }
        check(leftovers.isEmpty()) { "unconsumed scripted responses: $leftovers" }
    }
}
