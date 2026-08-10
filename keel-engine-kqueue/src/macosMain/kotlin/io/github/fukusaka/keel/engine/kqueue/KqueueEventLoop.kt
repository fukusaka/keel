package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.logging.error
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.AbstractPosixReadinessEventLoop
import io.github.fukusaka.keel.native.posix.FdReadyListener
import io.github.fukusaka.keel.native.posix.Interest
import io.github.fukusaka.keel.native.posix.InternalPosixEventLoopApi
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.native.posix.errnoMessage
import io.github.fukusaka.keel.pipeline.DeadlineScheduler
import io.github.fukusaka.keel.pipeline.IoTransport
import kotlinx.cinterop.Arena
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import platform.darwin.EVFILT_READ
import platform.darwin.EVFILT_WRITE
import platform.darwin.EV_EOF
import platform.posix.EAGAIN
import platform.posix.EDEADLK
import platform.posix.EINTR
import platform.posix.pthread_create
import platform.posix.pthread_equal
import platform.posix.pthread_join
import platform.posix.pthread_self
import platform.posix.pthread_tVar
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.resumeWithException
import kotlin.time.TimeSource

/**
 * Single-threaded kqueue event loop for macOS, also serving as a [CoroutineDispatcher].
 *
 * Drives all I/O for channels created by [KqueueEngine]. A dedicated
 * pthread runs [loop], interleaving three tasks:
 * 1. Execute queued coroutine continuations (the base's task queue)
 * 2. Call `kevent()` to wait for fd readiness events
 * 3. Resume suspended coroutines when their fds become ready
 *
 * **CoroutineDispatcher integration**: By extending [CoroutineDispatcher],
 * coroutines dispatched on this EventLoop (e.g., `launch(eventLoop) {}`)
 * execute entirely on the EventLoop thread. When `cont.resume()` is called,
 * the continuation is dispatched back to this same thread via [dispatch],
 * eliminating cross-thread dispatch overhead. This matches Netty's model
 * where channelRead/write run on the EventLoop thread.
 *
 * **Thread model**: The EventLoop thread is created via `pthread_create`
 * rather than Kotlin/Native's `Worker` (deprecated) or coroutine dispatchers
 * (unnecessary overhead for a tight syscall loop).
 *
 * **Wakeup mechanism**: A `pipe(2)` fd pair is registered with kqueue.
 * External threads call [wakeup] to write 1 byte to the pipe, causing
 * `kevent()` to return immediately so the EventLoop can process newly
 * registered fds or queued tasks.
 *
 * **Scalability**: Each EventLoop instance is single-threaded.
 * [KqueueEventLoopGroup] creates multiple instances and distributes
 * channels in round-robin for multi-threaded I/O.
 *
 * **Thread safety**: the registration ledger in the base class is
 * protected by a POSIX mutex.
 * The base's task queue is a lock-free MPSC queue — CAS-based
 * enqueue (~5-10ns) replaces mutex lock/unlock (~50-100ns) on the
 * dispatch hot path.
 *
 * ```
 * EventLoop thread (single loop iteration):
 *   1. drainTasks()    — run coroutine continuations
 *   2. kevent(timeout) — block until events or wakeup
 *      timeout = 0 if tasks pending, null otherwise
 *   3. for each ready fd:
 *        if wakeup pipe: consume byte, continue
 *        dispatchReady(fd, interest):
 *          pipeline path: callback.onReady(), then onPeerClosed() on EV_EOF
 *          suspend path:  pop FIFO head
 *          either path:   EV_DELETE unless a callback *or* a waiter remains
 *                         on the key; if neither was there → WARN + EV_DELETE
 * ```
 */
@OptIn(ExperimentalForeignApi::class, InternalPosixEventLoopApi::class)
internal class KqueueEventLoop(
    override val logger: Logger,
    /**
     * Per-EventLoop [BufferAllocator] instance. Co-located with the loop
     * (rather than tracked separately in [KqueueEventLoopGroup]) so callers
     * receive the allocator-loop pair as a single object — eliminating the
     * `Pair<EventLoop, BufferAllocator>` allocation that the previous
     * `EventLoopGroup.next()` API created on every accept. Default is
     * [DefaultAllocator] for boss / test loops that do not perform reads
     * and therefore never invoke the allocator.
     */
    val allocator: BufferAllocator = DefaultAllocator,
    /**
     * Engine-wide default read buffer size
     * ([io.github.fukusaka.keel.core.IoEngineConfig.readBufferSize]) for
     * connections on this loop. Used as the fallback when a connection's
     * [io.github.fukusaka.keel.core.BindConfig.readBufferSize] /
     * [io.github.fukusaka.keel.core.ConnectConfig.readBufferSize] is `null`;
     * the effective size is captured per connection on the transport.
     */
    val readBufferSize: Int = IoTransport.DEFAULT_READ_BUFFER_SIZE,
    /**
     * Engine-wide default idle (no-progress) timeout in milliseconds
     * ([io.github.fukusaka.keel.core.IoEngineConfig.idleTimeoutMillis]) for
     * connections on this loop (`0` = disabled). Fallback when a connection's
     * [io.github.fukusaka.keel.core.BindConfig.idleTimeoutMillis] /
     * [io.github.fukusaka.keel.core.ConnectConfig.idleTimeoutMillis] is `null`.
     */
    val idleTimeoutMillis: Long = 0,
    /**
     * Engine-wide [io.github.fukusaka.keel.core.IoEngineConfig.flushCoalescing]
     * value. When `true` (default), [KqueueIoTransport.flush] schedules the
     * actual send onto the next EL tick via [dispatch] so that same-tick
     * per-emit `requestFlush` calls collapse into one `writev(2)`. When
     * `false`, each `flush()` sends immediately (pre-#899 behaviour).
     */
    val flushCoalescing: Boolean = true,
    private val syscallOps: KqueueSyscallOps = PosixKqueueSyscallOps,
) : AbstractPosixReadinessEventLoop(), KqueueSuspendRegister {

    /**
     * The kqueue file descriptor, created at construction.
     * Exposed for [KqueueEngine.bind] to register server fds directly
     * via `kevent(kqFd, ...)`. Channel fds are registered via [register].
     */
    val kqFd: Int

    // Pre-allocated event carrier array reused across every [loop]
    // iteration. Sized to [MAX_EVENTS]; each slot is a mutable [KqEvent]
    // whose fields are overwritten by [KqueueSyscallOps.waitEvents].
    // Only accessed from the EventLoop thread.
    private val eventBuffer: Array<KqEvent> = Array(MAX_EVENTS) { KqEvent() }

    /**
     * Shared gather-write scratch: one native (bases, lens) pair reused by
     * every transport flush on this loop (see [KqueueIoTransport.flushGather]). Kept per
     * EventLoop rather than per connection so every flush on this thread
     * touches the same hot memory — mirroring the locality of the memScoped
     * arena this replaced — while performing no per-flush allocation.
     * EL-confined like [eventBuffer]; freed once in [close] (safe: the loop
     * thread is joined there, so no flush can be in flight).
     */
    internal var writevBases: CPointer<CPointerVar<ByteVar>> = nativeHeap.allocArray(INITIAL_WRITEV_CAPACITY)
        private set

    /** Byte lengths (`size_t`) paired with [writevBases]. */
    internal var writevLens: CPointer<ULongVar> = nativeHeap.allocArray(INITIAL_WRITEV_CAPACITY)
        private set

    private var writevCapacity: Int = INITIAL_WRITEV_CAPACITY

    /**
     * Grows [writevBases] / [writevLens] (1.5x, at least [n]) so a gather
     * flush of [n] regions fits. EventLoop thread only — callers run inside
     * the flush path, which is confined to this loop.
     */
    internal fun ensureWritevCapacity(n: Int) {
        if (writevCapacity >= n) return
        val grown = maxOf(writevCapacity + (writevCapacity shr 1), n)
        nativeHeap.free(writevBases)
        nativeHeap.free(writevLens)
        writevBases = nativeHeap.allocArray(grown)
        writevLens = nativeHeap.allocArray(grown)
        writevCapacity = grown
    }

    private val wakeupFds = IntArray(2) // [readFd, writeFd]

    // Cached byte arrays to avoid per-wakeup allocation.
    // wakeup() is called once per dispatch/register, so reuse matters.
    private val wakeupWriteBuf = byteArrayOf(1)
    private val wakeupReadBuf = ByteArray(WAKEUP_DRAIN_SIZE)
    private val running = AtomicInt(1) // 1 = running, 0 = stopped

    // Whether `pthread_create` ever succeeded for this loop. A loop can be
    // closed without one: a group whose `start()` fails part way leaves the
    // rest of it constructed and idle, and tests build loops they never
    // start. There is nothing to join in that case, and nothing that will
    // ever run the teardown -- so `close()` runs it here instead.
    private val threadCreated = AtomicInt(0)

    /** Backs [threadPtr]. Cleared in [close] — after joining the loop thread, or at once when none was created. */
    private val arena = Arena()

    private val threadPtr = arena.alloc<pthread_tVar>()

    init {
        val fd = syscallOps.kqueueCreate()
        if (fd < 0) error("kqueue() failed: ${errnoMessage(-fd)}")
        kqFd = fd

        // Create wakeup pipe and register the read end with kqueue
        val pipeErr = syscallOps.makePipe(wakeupFds)
        if (pipeErr != 0) {
            closeFdSafely(kqFd, logger, "kqueue init (pipe failure)")
            error("pipe() failed: ${errnoMessage(pipeErr)}")
        }
        syscallOps.setNonBlocking(wakeupFds[0])
        syscallOps.setNonBlocking(wakeupFds[1])

        val kevErr = syscallOps.addReadFilter(kqFd, wakeupFds[0])
        if (kevErr != 0) {
            closeFdSafely(wakeupFds[0], logger, "kqueue init (kevent failure)")
            closeFdSafely(wakeupFds[1], logger, "kqueue init (kevent failure)")
            closeFdSafely(kqFd, logger, "kqueue init (kevent failure)")
            error("kevent(EV_ADD, wakeupFd) failed: ${errnoMessage(kevErr)}")
        }
    }

    // --- CoroutineDispatcher ---

    override fun inEventLoop(): Boolean {
        val t = eventLoopThread ?: return false
        return pthread_equal(pthread_self(), t) != 0
    }

    // --- Channel registration ---

    /**
     * Starts the EventLoop thread. Must be called once after construction.
     * The thread runs [loop] until [close] is called.
     *
     * A call on a loop whose termination has already been claimed — by
     * [close], or by a [loop] that ended on its own — is reported and ignored
     * rather than starting anything: the resources a thread would need are
     * already released, or about to be. The check is sequential, not a
     * synchronisation point; nothing in the tree starts a loop concurrently
     * with closing it, and this does not make that safe.
     */
    fun start() {
        if (isTerminationClaimed()) {
            // Someone already owns this loop's end: a `close()` that released
            // the arena `threadPtr` lives in, or a `loop()` that ran and
            // returned. Creating a thread here would write through that
            // pointer -- and the thread would find the claim taken and return
            // without doing anything anyway. Reported rather than thrown:
            // `close()` is idempotent and this is the same kind of late call.
            logger.error { "${this::class.simpleName}.start() on a loop whose termination is already claimed is ignored" }
            return
        }
        val ref = StableRef.create(this)
        // Before the call, not after: the new thread can run to completion
        // while this one is still between the two statements, and a `close()`
        // reading 0 in that window would skip the join and free the arena and
        // the fds out from under a live loop. Set pessimistically and cleared
        // if the thread never came into being.
        //
        // The inverse window is open and not closed by this: a `close()`
        // landing between here and `pthread_create` returning reads 1 and
        // joins a slot not yet written. Neither window is reachable while
        // `start()` is only called from an engine constructor, before any
        // reference has escaped; this is ordering, not synchronisation.
        threadCreated.value = 1
        val rc = pthread_create(
            threadPtr.ptr,
            null,
            staticCFunction { arg ->
                val el = arg!!.asStableRef<KqueueEventLoop>().get()
                el.loop()
                arg.asStableRef<KqueueEventLoop>().dispose()
                null
            },
            ref.asCPointer(),
        )
        if (rc != 0) {
            // No thread came into being, so nothing will join it and nothing
            // will run this loop's teardown unless `close()` does.
            threadCreated.value = 0
            // pthread_create returns the errno-like code directly; errno is not set.
            ref.dispose()
            error("pthread_create() failed: ${errnoMessage(rc)}")
        }
    }

    /**
     * EventLoop-thread submission of EV_ADD for [fd]. Resumes [cont] with
     * an exception on failure (after removing [reg] from the chain at [key]).
     *
     * [key] is computed by the caller (`register()`) so the error path
     * does not recompute `registrationKey(fd, interest)`.
     *
     * @param reg The Registration to remove on submit failure.
     */
    override fun submitArm(
        fd: Int,
        interest: Interest,
        key: Long,
        reg: Registration,
        cont: CancellableContinuation<Unit>,
    ) {
        assertInEventLoop("submitArm")
        // The arm is dispatched after the chain append releases the lock, so a
        // close() can queue its teardown in between: cancelAll then resumes
        // this waiter and the fd is closed before this runs. Arming here would
        // touch a descriptor that is gone — or, once the kernel reuses the
        // number, somebody else's — and leave a ledger entry for it, which is
        // the stale-registration hang the teardown ordering exists to prevent.
        // A waiter no longer in the chain has already been resumed; drop it.
        if (!withRegLock { isRegistered(key, reg) }) return

        val kevErr = when (interest) {
            Interest.READ -> syscallOps.addReadFilter(kqFd, fd)
            Interest.WRITE -> syscallOps.addWriteFilter(kqFd, fd)
        }
        if (kevErr != 0) {
            withRegLock { removeRegistration(key, reg) }
            cont.resumeWithException(
                IllegalStateException("kevent(EV_ADD, fd=$fd) failed: ${errnoMessage(kevErr)}"),
            )
        }
    }

    /** `internal` wrapper for this module's `EventLoopGroup`; see [hasCallbackFor]. */
    internal fun hasCallbackRegistration(fd: Int, interest: Interest): Boolean =
        hasCallbackFor(fd, interest)

    /** `internal` wrapper for this module's probes; see [participantCount]. */
    internal fun participants(): Int = participantCount()

    /**
     * Arms [fd] + [interest] for the pipeline path with a persistent `EV_ADD`.
     *
     * A failed arm withdraws the listener through [key] and logs at ERROR: there
     * is no continuation to fail, and a listener left in the ledger with nothing
     * armed never fires again. epoll's override does the same.
     */
    override fun submitArmCallback(fd: Int, interest: Interest, key: Long, listener: FdReadyListener) {
        assertInEventLoop("submitArmCallback")
        val kevErr = when (interest) {
            Interest.READ -> syscallOps.addReadFilter(kqFd, fd)
            Interest.WRITE -> syscallOps.addWriteFilter(kqFd, fd)
        }
        if (kevErr != 0) {
            withdrawFailedCallbackArm(fd, interest, key, listener, "kevent(EV_ADD)", kevErr)
        }
    }

    // --- Wakeup ---

    /**
     * Wakes up the EventLoop thread by writing 1 byte to the wakeup pipe.
     * Called after [register] or [dispatch] to ensure `kevent()` re-evaluates
     * pending fds and tasks.
     */
    override fun wakeup() {
        val err = syscallOps.wakeupWrite(wakeupFds[1], wakeupWriteBuf)
        // EAGAIN: pipe buffer full — a wakeup is already pending, which
        // is exactly what we want. Benign.
        if (err != 0 && err != EAGAIN) {
            logger.debug { "kqueue wakeup write() failed: ${errnoMessage(err)}" }
        }
    }

    /**
     * Consumes all bytes from the wakeup pipe's read end.
     * Called from the EventLoop thread when the wakeup fd fires.
     */
    private fun consumeWakeup() {
        val err = syscallOps.wakeupDrain(wakeupFds[0], wakeupReadBuf)
        if (err != 0) {
            logger.debug { "kqueue wakeup read() failed: ${errnoMessage(err)}" }
        }
    }

    // --- Event loop ---

    /**
     * The EventLoop's main loop, running on a dedicated pthread.
     *
     * Each iteration:
     * 1. [drainTasks] — execute queued coroutine continuations
     * 2. `kevent()` — wait for fd readiness events (non-blocking if tasks
     *    are pending, blocking otherwise)
     * 3. Process ready fds — resume associated coroutine continuations
     */
    // --- Idle/read deadline timer (progress-bound mechanism) ---

    private val timeOrigin = TimeSource.Monotonic.markNow()

    private fun nowMillis(): Long = timeOrigin.elapsedNow().inWholeMilliseconds

    /**
     * Per-EventLoop deadline timer backing the transport idle (no-progress) timeout.
     * Confined to this EventLoop thread: transports on this loop schedule / touch /
     * cancel idle deadlines through it, [loop] drives the `kevent` timeout from
     * [DeadlineScheduler.nextDeadlineMillis], and fires due timers via
     * [DeadlineScheduler.expireDue] after each wake.
     */
    internal val deadlineScheduler = DeadlineScheduler(::nowMillis, logger)

    override fun loopBody() {
        while (running.value != 0) {
            // The registration lock failing means the ledgers stopped being
            // exclusive; end the loop the same way a poll fatal does rather
            // than keep arming from state nothing is guarding.
            if (regLockBroken()) break
            drainTasks()

            // Non-blocking poll if tasks arrived during drainTasks(), else block
            // until events / wakeup / the next connection deadline (whichever first).
            val timeout = computeWaitTimeout()
            val n = syscallOps.waitEvents(kqFd, eventBuffer, timeout)
            if (n < 0) {
                // Negative return encodes -errno per KqueueSyscallOps contract.
                val err = -n
                // EINTR: interrupted by signal (e.g. debugger attach).
                // EAGAIN: spurious wakeup. Both are retriable.
                if (err == EINTR || err == EAGAIN) continue
                // Fatal error — log and terminate the EventLoop thread.
                // Cannot throw from a pthread; logger is the only output path.
                logger.error { "kevent() fatal error: ${errnoMessage(err)}" }
                break
            }
            for (i in 0 until n) {
                val ev = eventBuffer[i]
                val fd = ev.fd

                if (fd == wakeupFds[0]) {
                    consumeWakeup()
                    continue
                }

                val interest = when (ev.filter) {
                    EVFILT_READ -> Interest.READ
                    EVFILT_WRITE -> Interest.WRITE
                    else -> continue
                }
                // EV_EOF surfaces peer-FIN / peer-RST regardless of which filter
                // is armed. Pass it to the listener so write-only push clients
                // (`PipelinedChannel.readEnabled = false`) can still detect
                // peer close: see `KqueueIoTransport.onPeerClosed` for how the
                // signal reaches `IoTransport.onReadClosed`.
                val eofFlag = (ev.flags and EV_EOF) != 0
                dispatchReady(fd, interest, eofFlag)
            }
            // Fire any connection idle/read deadlines that elapsed during the wait.
            deadlineScheduler.expireDue(nowMillis())
        }
    }

    /**
     * Computes the `kevent` timeout (ms): `0` if tasks are pending (non-blocking
     * poll), otherwise the time until the nearest connection deadline, or
     * [KqueueSyscallOps.TIMEOUT_BLOCK] to block indefinitely when none is scheduled.
     * A non-positive remaining time clamps to `0` so an already-elapsed deadline is
     * serviced on the next [DeadlineScheduler.expireDue] without blocking.
     */
    private fun computeWaitTimeout(): Long {
        if (hasTasksPending()) return 0L
        val next = deadlineScheduler.nextDeadlineMillis()
        if (next == Long.MAX_VALUE) return KqueueSyscallOps.TIMEOUT_BLOCK
        return (next - nowMillis()).coerceAtLeast(0L)
    }

    /**
     * Removes [fd]'s kqueue filter for [interest] (`EV_DELETE`).
     *
     * Called from [dispatchReady] on both the pipeline path (when a WRITE callback
     * does not re-register, indicating flush success) and the suspend path (when the
     * registration chain empties). Prevents level-triggered busy-loops by removing
     * the filter until the caller arms again.
     */
    override fun removeInterest(fd: Int, interest: Interest) {
        val err = when (interest) {
            Interest.READ -> syscallOps.deleteReadFilter(kqFd, fd)
            Interest.WRITE -> syscallOps.deleteWriteFilter(kqFd, fd)
        }
        if (err != 0) {
            logger.debug {
                "kevent(EV_DELETE, fd=$fd, ${interest.name}) failed: ${errnoMessage(err)}"
            }
        }
    }

    // --- Lifecycle ---

    /**
     * Stops the EventLoop and releases all resources.
     *
     * Stops the loop, then closes the
     * kqueue fd and wakeup pipe fds. A loop with a thread is signalled and joined;
     * one that never started is taken apart here. Waiters still parked at
     * that point are
     * ended by the loop itself, on its way out — or, for a loop that never
     * started, by this thread; see below.
     *
     * **Must not be called from the EventLoop thread.** `pthread_join` returns
     * `EDEADLK` at once when asked to join the caller, so everything below
     * would run while the loop is still inside its own body — closing the fds
     * it is about to use and freeing the scratch it writes through. That one
     * errno is treated as fatal misuse: it is logged and nothing is released,
     * because releasing is what would corrupt. Every other join
     * failure falls through and releases anyway, since the `running` CAS above
     * has already fired and no later `close()` can pick it up.
     *
     * **A loop that was never started takes a different path**: there is no
     * thread to signal or join, and nothing will ever drain what was dispatched
     * at it, so this runs the loop's terminal sequence itself before releasing.
     * Which path is taken is decided by an explicit flag, not by inspecting
     * `threadPtr`: that slot comes from an `Arena`, which does not zero, and
     * on this target `pthread_t` is a pointer, so the `t != null` guard below
     * would be deciding from whatever the slot happened to hold. The flag keeps
     * that guard off the never-started path entirely, so it is only ever asked
     * about a slot `pthread_create` has written.
     */
    fun close() {
        if (running.compareAndSet(1, 0)) {
            if (threadCreated.value == 0) {
                // No thread, so no drain is coming and nothing below can wait
                // for one. Whatever was dispatched at this loop -- a
                // transport's teardown, an accepted connection -- runs here or
                // never, and the descriptors it holds go with it.
                //
                // Releasing in a `catch` as well: the terminal sequence runs
                // application teardown, and a throw escaping it must not also
                // cost this loop its fds. On the joined path below that
                // question cannot arise -- a throw there leaves a pthread
                // entry point and ends the process.
                try {
                    finishWithoutRunning()
                } catch (terminationFailure: Throwable) {
                    releaseLoopResources()
                    throw terminationFailure
                }
                // Never fall through to the join. A `false` above means the
                // claim was already taken -- and with no thread created, the
                // only taker is a direct `loop()` call, which leaves nothing
                // to join: `threadPtr` was never written, and handing that
                // slot to `pthread_join` reads whatever the `Arena` happened
                // to hold. The seam suites take exactly this path, calling
                // `loop()` on their own thread and then `close()`.
                //
                // Checked rather than assumed: that `loop()` must have
                // returned before this call. If it has not, releasing here
                // would close the fds and free the arena underneath it, and
                // the `EDEADLK` guard that catches "closing from the loop
                // thread" is on the join path, which this branch skips.
                // Quiescence is what says the sequence finished, whoever ran
                // it.
                if (!isStopped()) {
                    // Someone else's sequence is still running. Nudge it: this
                    // is the only thing that tells a loop parked in the kernel
                    // wait that `running` went down, and without it the
                    // quiescence this branch is refusing over may never
                    // arrive. Then leave everything open -- a later `close()`
                    // cannot retry, since the flag is already down, but a
                    // running loop that finishes will at least have finished.
                    wakeup()
                    logger.error {
                        "${this::class.simpleName}.close() found the loop still being taken apart by " +
                            "another caller; releasing nothing, because releasing is what would corrupt"
                    }
                    return
                }
                releaseLoopResources()
                return
            }
            wakeup()
            // Join the EventLoop thread. Reached only with `threadCreated` set,
            // so `pthread_create` was called and wrote `threadPtr`.
            val t = threadPtr.ptr[0]
            if (t != null) {
                val joinRet = pthread_join(t, null)
                if (joinRet == EDEADLK) {
                    logger.error {
                        "close() was called from this EventLoop's own thread — releasing nothing, " +
                            "because the loop is still running and would lose the fds it is using"
                    }
                    return
                }
                if (joinRet != 0) {
                    logger.warn { "pthread_join() failed: ${errnoMessage(joinRet)}" }
                }
            }
            releaseLoopResources()
        }
    }

    /**
     * Releases what the loop owned once nothing will run on it again: its
     * kernel interface, its wakeup channel, the arena behind them, and the
     * per-loop allocator child.
     *
     * Shared by the two ways a loop ends — its thread returning and being
     * joined, or [finishWithoutRunning] taking it apart for a loop that never
     * had one. The joined path reaches here only after the terminal sequence
     * has run and the thread is gone. The other reaches it after *a* sequence
     * has run, which is this caller's unless something else claimed it first —
     * see the obligation stated at that call site.
     */
    private fun releaseLoopResources() {
        closeFdSafely(wakeupFds[0], logger, "event loop teardown (wakeupFds[0])")
        closeFdSafely(wakeupFds[1], logger, "event loop teardown (wakeupFds[1])")
        closeFdSafely(kqFd, logger, "event loop teardown (kqFd)")
        // The registration lock is deliberately not destroyed or freed:
        // a cancellation arriving after this point takes it, and those
        // arrive without bound (see AbstractPosixReadinessEventLoop's
        // regMutex). The task queue is lock-free, so it has none either.
        arena.clear()
        // Close the per-EL allocator child. By construction the
        // EventLoopGroup hands each EL the result of
        // `BufferAllocator.createChild()`, so closing here drains
        // this loop's freelists and runs `Freelist.close()` (mutex
        // destroy / nativeHeap.free for `MutexFreelist`). The thread that ran
        // this loop -- joined by now, or this very thread having run it --
        // completed the terminal sequence, so it can no longer allocate — but a returnToPool can still
        // arrive from a post-quiescence closing caller (the stopped-loop
        // transport teardown releases pending writes on its own thread);
        // that race is the allocator's to absorb, via its cross-thread
        // return queue's close-sentinel contract. Default no-op for
        // `DefaultAllocator` (tests that instantiate this loop with the
        // stateless allocator).
        // Free the shared writev scratch arrays. Either the loop thread is
        // joined by the caller above, or there never was one, so no transport
        // flush can touch them.
        nativeHeap.free(writevBases)
        nativeHeap.free(writevLens)
        allocator.close()
    }

    // --- KqueueSuspendRegister impl (seam for connect InProgress) ---

    override suspend fun awaitWriteReady(fd: Int, logger: Logger) = awaitWritableOwningFd(fd, logger)

    companion object {

        /** Initial capacity of the shared writev scratch arrays (grows 1.5x). */
        const val INITIAL_WRITEV_CAPACITY = 8

        /**
         * Maximum events per kevent() call. 64 balances memory usage
         * (64 * sizeof(kevent) = ~2.5 KiB on arm64) against reducing
         * the number of kevent() syscalls under high fd counts.
         * Netty uses 4096; 64 is conservative for initial implementation.
         */
        private const val MAX_EVENTS = 64

        /** Drain buffer size for consumeWakeup(). Matches pipe FIFO default. */
        private const val WAKEUP_DRAIN_SIZE = 64
    }
}
