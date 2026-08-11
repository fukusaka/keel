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
 * Single-threaded only. Engine tests normally drive the system under
 * test on a single coroutine; if cross-thread access is needed, wrap
 * in `synchronized` at the call site.
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
    public var defaultRead: ReadResult = ReadResult.WouldBlock

    /** Returned by [write] / [writev] / [send] when no response is queued. */
    public var defaultWrite: WriteResult = WriteResult.WouldBlock

    /** Returned by [accept] when no response is queued for the server fd. */
    public var defaultAccept: AcceptResult = AcceptResult.WouldBlock

    /** Returned by [connect] when no response is queued for the fd. */
    public var defaultConnect: ConnectResult = ConnectResult.InProgress

    /** Returned by [shutdown] when no response is queued for the fd. */
    public var defaultShutdown: ShutdownResult = ShutdownResult.Ok

    /** Returned by [close] when no response is queued for the fd. */
    public var defaultClose: CloseResult = CloseResult.Ok

    // --- Call tracking ---

    /** Number of times each syscall was invoked, summed across all fds. */
    public var readCalls: Int = 0
        private set
    public var writeCalls: Int = 0
        private set
    public var writevCalls: Int = 0
        private set
    public var sendCalls: Int = 0
        private set
    public var acceptCalls: Int = 0
        private set
    public var connectCalls: Int = 0
        private set
    public var shutdownCalls: Int = 0
        private set
    public var closeCalls: Int = 0
        private set

    private val _closedFds = mutableListOf<Int>()

    /**
     * Ordered list of fds passed to [close], in invocation order.
     * Duplicates are preserved — a double-close shows up as two entries
     * so tests can assert idempotence or detect leaks. See
     * [assertNoDoubleClose] for the common assertion.
     */
    public val closedFds: List<Int> get() = _closedFds.toList()

    // --- NativeSocket impl ---

    /**
     * Thrown by the next [read] call, then cleared.
     *
     * Stands in for the read path failing between the buffer allocation and
     * the hand-off to the transport's reader — the window in which a throw
     * leaks a pooled buffer no one else can reach. One-shot so a test can
     * assert what the connection does afterwards.
     */
    public var readThrowsOnce: Throwable? = null

    /**
     * Thrown by the next [accept] call, then cleared.
     *
     * Stands in for the accept loop failing somewhere other than the per-socket
     * setup it guards individually, which is what decides whether the listener
     * is left armed. One-shot for the same reason as [readThrowsOnce].
     */
    public var acceptThrowsOnce: Throwable? = null

    override fun read(fd: Int, buf: CPointer<ByteVar>, length: Int): ReadResult {
        readCalls++
        readThrowsOnce?.let {
            readThrowsOnce = null
            throw it
        }
        return readQueue[fd]?.removeFirstOrNull() ?: defaultRead
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
    public var flushThrowsOnce: Throwable? = null

    /**
     * Write / writev / send calls let through before [flushThrowsOnce] fires.
     *
     * Zero — the default — throws on the next call, which is what a test that
     * only wants "the flush failed" asks for. A non-zero count reaches the
     * throw that lands *after* bytes have already gone out, which is a
     * different state for the caller to be in: a single-buffer flush that
     * throws part way owes the queue the remainder rather than the whole
     * entry, and both loops run inside one `flush()`, so there is no moment
     * between them for a test to arm the fault itself.
     */
    public var flushThrowsAfterCalls: Int = 0

    private fun failFlushIfScripted() {
        flushThrowsOnce?.let {
            if (flushThrowsAfterCalls > 0) {
                flushThrowsAfterCalls--
                return
            }
            flushThrowsOnce = null
            throw it
        }
    }

    override fun write(fd: Int, buf: CPointer<ByteVar>, length: Int): WriteResult {
        writeCalls++
        failFlushIfScripted()
        return writeQueue[fd]?.removeFirstOrNull() ?: defaultWrite
    }

    override fun writev(
        fd: Int,
        bases: CPointer<CPointerVar<ByteVar>>,
        lens: CPointer<ULongVar>,
        count: Int,
    ): WriteResult {
        writevCalls++
        failFlushIfScripted()
        return writevQueue[fd]?.removeFirstOrNull() ?: defaultWrite
    }

    override fun accept(serverFd: Int): AcceptResult {
        acceptCalls++
        acceptThrowsOnce?.let {
            acceptThrowsOnce = null
            throw it
        }
        return acceptQueue[serverFd]?.removeFirstOrNull() ?: defaultAccept
    }

    override fun connect(fd: Int, addr: CPointer<ByteVar>, addrLen: Int): ConnectResult {
        connectCalls++
        return connectQueue[fd]?.removeFirstOrNull() ?: defaultConnect
    }

    override fun send(fd: Int, buf: CPointer<ByteVar>, length: Int, flags: Int): WriteResult {
        sendCalls++
        failFlushIfScripted()
        return sendQueue[fd]?.removeFirstOrNull() ?: defaultWrite
    }

    override fun shutdown(fd: Int, how: Int): ShutdownResult {
        shutdownCalls++
        return shutdownQueue[fd]?.removeFirstOrNull() ?: defaultShutdown
    }

    override fun close(fd: Int): CloseResult {
        closeCalls++
        _closedFds.add(fd)
        return closeQueue[fd]?.removeFirstOrNull() ?: defaultClose
    }

    // --- Script setup ---

    /** Appends [results] to the scripted queue for `read(fd, ...)`. */
    public fun enqueueRead(fd: Int, vararg results: ReadResult) {
        readQueue.getOrPut(fd) { ArrayDeque() }.addAll(results)
    }

    /** Appends [results] to the scripted queue for `write(fd, ...)`. */
    public fun enqueueWrite(fd: Int, vararg results: WriteResult) {
        writeQueue.getOrPut(fd) { ArrayDeque() }.addAll(results)
    }

    /** Appends [results] to the scripted queue for `writev(fd, ...)`. */
    public fun enqueueWritev(fd: Int, vararg results: WriteResult) {
        writevQueue.getOrPut(fd) { ArrayDeque() }.addAll(results)
    }

    /** Appends [results] to the scripted queue for `send(fd, ...)`. */
    public fun enqueueSend(fd: Int, vararg results: WriteResult) {
        sendQueue.getOrPut(fd) { ArrayDeque() }.addAll(results)
    }

    /** Appends [results] to the scripted queue for `accept(serverFd)`. */
    public fun enqueueAccept(serverFd: Int, vararg results: AcceptResult) {
        acceptQueue.getOrPut(serverFd) { ArrayDeque() }.addAll(results)
    }

    /** Appends [results] to the scripted queue for `connect(fd, ...)`. */
    public fun enqueueConnect(fd: Int, vararg results: ConnectResult) {
        connectQueue.getOrPut(fd) { ArrayDeque() }.addAll(results)
    }

    /** Appends [results] to the scripted queue for `shutdown(fd, how)`. */
    public fun enqueueShutdown(fd: Int, vararg results: ShutdownResult) {
        shutdownQueue.getOrPut(fd) { ArrayDeque() }.addAll(results)
    }

    /** Appends [results] to the scripted queue for `close(fd)`. */
    public fun enqueueClose(fd: Int, vararg results: CloseResult) {
        closeQueue.getOrPut(fd) { ArrayDeque() }.addAll(results)
    }

    // --- Assertion helpers ---

    /**
     * Throws if every fd in [closedFds] is NOT unique. Useful as a
     * teardown-order sanity check. Quiet when close was never called.
     */
    public fun assertNoDoubleClose() {
        val seen = mutableSetOf<Int>()
        for (fd in _closedFds) {
            check(seen.add(fd)) { "fd=$fd was closed more than once: $_closedFds" }
        }
    }

    /**
     * Throws if any scripted response queue is non-empty, or if a scripted
     * fault never fired. Call at the end of a test to verify every queued
     * response was consumed — an unconsumed response usually means the system
     * under test short-circuited before reaching that branch.
     *
     * The faults are checked for the same reason the queues are, and it is the
     * sharper of the two: a test whose fault never fires asserts what a failure
     * costs against a run that had no failure in it, and passes against a build
     * that never fixed anything.
     */
    public fun assertAllConsumed() {
        check(flushThrowsOnce == null) {
            "a scripted flush failure never fired" +
                if (flushThrowsAfterCalls > 0) " (still waiting out $flushThrowsAfterCalls calls)" else ""
        }
        check(acceptThrowsOnce == null) { "a scripted accept failure never fired" }
        check(readThrowsOnce == null) { "a scripted read failure never fired" }
        val leftovers = buildList {
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
        check(leftovers.isEmpty()) { "unconsumed scripted responses: $leftovers" }
    }
}
