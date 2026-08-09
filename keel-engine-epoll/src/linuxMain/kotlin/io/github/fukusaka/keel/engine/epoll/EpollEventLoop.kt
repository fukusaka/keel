package io.github.fukusaka.keel.engine.epoll

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
import platform.linux.EPOLLERR
import platform.linux.EPOLLHUP
import platform.linux.EPOLLIN
import platform.linux.EPOLLOUT
import platform.linux.EPOLLRDHUP
import platform.posix.EAGAIN
import platform.posix.EDEADLK
import platform.posix.EEXIST
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
 * Single-threaded epoll event loop for Linux, also serving as a [CoroutineDispatcher].
 *
 * Drives all I/O for channels created by [EpollEngine]. A dedicated
 * pthread runs [loop], interleaving three tasks:
 * 1. Execute queued coroutine continuations (the base's task queue)
 * 2. Call `epoll_wait()` to wait for fd readiness events
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
 * **Wakeup mechanism**: An `eventfd(2)` is registered with epoll.
 * External threads call [wakeup] to signal the eventfd, causing
 * `epoll_wait()` to return immediately so the EventLoop can process
 * newly registered fds or queued tasks. eventfd is more efficient than
 * pipe(2) on Linux: single fd instead of two, and kernel-optimized
 * for signaling.
 *
 * **Scalability**: Each EventLoop instance is single-threaded.
 * [EpollEventLoopGroup] creates multiple instances and distributes
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
 *   1. drainTasks()        — run coroutine continuations
 *   2. epoll_wait(timeout) — block until events or wakeup
 *      timeout = 0 if tasks pending, -1 otherwise
 *   3. for each ready fd:
 *        if eventfd: consume, continue
 *        lookup registration -> remove -> continuation.resume(Unit)
 * ```
 */
@OptIn(ExperimentalForeignApi::class, InternalPosixEventLoopApi::class)
internal class EpollEventLoop(
    override val logger: Logger,
    /**
     * Per-EventLoop [BufferAllocator] instance. Co-located with the loop
     * (rather than tracked separately in [EpollEventLoopGroup]) so callers
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
     * value. When `true` (default), [EpollIoTransport.flush] schedules the
     * actual send onto the next EL tick via [dispatch] so that same-tick
     * per-emit `requestFlush` calls collapse into one `writev(2)`. When
     * `false`, each `flush()` sends immediately (pre-#900 behaviour).
     */
    val flushCoalescing: Boolean = true,
    private val syscallOps: EpollSyscallOps = PosixEpollSyscallOps,
) : AbstractPosixReadinessEventLoop(), EpollSuspendRegister {

    /**
     * The epoll file descriptor, created at construction.
     * Exposed for [EpollEngine.bind] to register server fds directly
     * via `epoll_ctl(epFd, ...)`. Channel fds are registered via [register].
     */
    val epFd: Int

    // Tracks the current epoll events per fd. epoll manages fds (not fd+interest
    // pairs), so ADD/MOD must specify all active interest bits at once.
    private val fdEvents = mutableMapOf<Int, Int>()

    // Pre-allocated event carrier array reused across every [loop]
    // iteration. Sized to [MAX_EVENTS]; each slot is a mutable [EpEvent]
    // whose fields are overwritten by [EpollSyscallOps.waitEvents].
    // Only accessed from the EventLoop thread.
    private val eventBuffer: Array<EpEvent> = Array(MAX_EVENTS) { EpEvent() }

    /**
     * Shared gather-write scratch: one native (bases, lens) pair reused by
     * every transport flush on this loop (see [EpollIoTransport.flushGather]). Kept per
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

    private val wakeupFd: Int
    private val running = AtomicInt(1) // 1 = running, 0 = stopped

    // Whether `pthread_create` ever succeeded for this loop. A loop can be
    // closed without one: a group whose `start()` fails part way leaves the
    // rest of it constructed and idle, and tests build loops they never
    // start. There is nothing to join in that case, and nothing that will
    // ever run the teardown -- so `close()` runs it here instead.
    private val threadCreated = AtomicInt(0)

    /** Backs [threadPtr]. Cleared in [close] once the loop thread is joined. */
    private val arena = Arena()

    private val threadPtr = arena.alloc<pthread_tVar>()

    init {
        val fd = syscallOps.epollCreate()
        if (fd < 0) error("epoll_create1() failed: ${errnoMessage(-fd)}")
        epFd = fd

        // Create eventfd for wakeup and register with epoll.
        // eventfd is more efficient than pipe on Linux: single fd,
        // kernel-optimized for event signaling.
        val wf = syscallOps.eventfdCreate()
        if (wf < 0) {
            closeFdSafely(epFd, logger, "epoll init (eventfd failure)")
            error("eventfd() failed: ${errnoMessage(-wf)}")
        }
        wakeupFd = wf

        val ctlErr = syscallOps.epollAdd(epFd, wakeupFd, EPOLLIN)
        if (ctlErr != 0) {
            closeFdSafely(wakeupFd, logger, "epoll init (epoll_ctl failure)")
            closeFdSafely(epFd, logger, "epoll init (epoll_ctl failure)")
            error("epoll_ctl(ADD, wakeupFd) failed: ${errnoMessage(ctlErr)}")
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
     */
    fun start() {
        if (running.value == 0) {
            // Closed before it ever ran, so its arena is already released --
            // and `threadPtr` lives in that arena. Creating a thread here
            // would write through a dangling pointer, and the thread would
            // find the loop's termination already claimed and return anyway.
            // Reported rather than thrown: `close()` is idempotent and this is
            // the same kind of late call.
            logger.error { "${this::class.simpleName}.start() on a closed loop is ignored" }
            return
        }
        val ref = StableRef.create(this)
        // Before the call, not after: the new thread can run to completion
        // while this one is still between the two statements, and a `close()`
        // reading 0 in that window would skip the join and free the arena and
        // the fds out from under a live loop. Set pessimistically and cleared
        // if the thread never came into being.
        threadCreated.value = 1
        val rc = pthread_create(
            threadPtr.ptr,
            null,
            staticCFunction { arg ->
                val el = arg!!.asStableRef<EpollEventLoop>().get()
                el.loop()
                arg.asStableRef<EpollEventLoop>().dispose()
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
     * Maps [interest] onto an epoll mask and arms it.
     *
     * READ asks for `EPOLLRDHUP` alongside `EPOLLIN` so a graceful peer FIN is
     * reported explicitly: the kernel delivers `EPOLLIN` for it (the read
     * returns 0), but `EPOLLHUP` only once both directions are closed, so
     * without `EPOLLRDHUP` the eof flag would reach `dispatchReady` only on a
     * full close and `onPeerClosed` would fire late.
     *
     * Requested here and only here, on a READ arm, so a listener with no READ
     * arm never sees it. Which connections hold one, and for how long, is
     * stated at the arm itself in `EpollIoTransport.init`.
     *
     * A failed arm withdraws the listener, as kqueue's does. On the first arm —
     * [addOrModifyEpoll] issues `ADD` and reaches `MOD` only on `EEXIST` — the
     * fd is left out of the interest list altogether and nothing is delivered
     * for it, so a retained listener would never fire. On the `MOD` path the fd
     * stays registered with its previous mask, so `EPOLLERR` / `EPOLLHUP` still
     * wake the loop; withdrawing means those take the no-handler branch, which
     * warns and disarms, rather than reaching a listener whose arm did not take.
     */
    override fun submitArmCallback(fd: Int, interest: Interest, key: Long, listener: FdReadyListener) {
        assertInEventLoop("submitArmCallback")
        val events = when (interest) {
            Interest.READ -> EPOLLIN or EPOLLRDHUP
            Interest.WRITE -> EPOLLOUT
        }
        val err = addOrModifyEpoll(fd, events)
        if (err != 0) {
            withdrawFailedCallbackArm(fd, interest, key, listener, "epoll_ctl", err)
        }
    }

    /**
     * EventLoop-thread submission for the suspend path. On failure the
     * [Registration] is removed and [cont] is resumed with the error, so a
     * waiter never suspends forever on an fd the loop failed to watch — the
     * same contract as `KqueueEventLoop.submitArm`.
     */
    override fun submitArm(
        fd: Int,
        interest: Interest,
        key: Long,
        reg: Registration,
        cont: CancellableContinuation<Unit>,
    ) {
        assertInEventLoop("submitArm")
        val events = when (interest) {
            Interest.READ -> EPOLLIN
            Interest.WRITE -> EPOLLOUT
        }
        // The arm is dispatched after the chain append releases the lock, so a
        // close() can queue its teardown in between: cancelAll then resumes
        // this waiter and the fd is closed before this runs. Arming here would
        // touch a descriptor that is gone — or, once the kernel reuses the
        // number, somebody else's — and leave a ledger entry for it, which is
        // the stale-registration hang the teardown ordering exists to prevent.
        // A waiter no longer in the chain has already been resumed; drop it.
        if (!withRegLock { isRegistered(key, reg) }) return

        val err = addOrModifyEpoll(fd, events)
        if (err != 0) {
            withRegLock { removeRegistration(key, reg) }
            cont.resumeWithException(
                IllegalStateException("epoll_ctl(ADD, fd=$fd) failed: ${errnoMessage(err)}"),
            )
        }
    }

    /** `internal` wrapper for this module's `EventLoopGroup`; see [hasCallbackFor]. */
    internal fun hasCallbackRegistration(fd: Int, interest: Interest): Boolean =
        hasCallbackFor(fd, interest)

    /** `internal` wrapper for this module's probes; see [participantCount]. */
    internal fun participants(): Int = participantCount()

    /**
     * Removes all tracking state for [fd] from [fdEvents].
     *
     * Called when a channel closes its fd. Prevents stale entries from
     * accumulating if the OS recycles the fd number for a new socket.
     * Does NOT call `epoll_ctl(DEL)` — closing the fd automatically
     * removes it from epoll.
     */
    fun cleanupFd(fd: Int) {
        withRegLock {
            fdEvents.remove(fd)
        }
    }

    /**
     * epoll keeps the mask in [fdEvents], so it has one to forget; the base
     * class's default does nothing because kqueue does not.
     */
    override fun forgetInterests(fd: Int) = cleanupFd(fd)

    // --- Wakeup ---

    /**
     * Wakes up the EventLoop thread by signaling the eventfd.
     * Called after [register] or [dispatch] to ensure `epoll_wait()`
     * re-evaluates pending fds and tasks.
     */
    override fun wakeup() {
        val err = syscallOps.eventfdWakeupWrite(wakeupFd)
        // EAGAIN: eventfd counter saturated — a wakeup is already
        // pending in the kernel, which is exactly what we want. Benign.
        if (err != 0 && err != EAGAIN) {
            logger.debug { "eventfd_write() failed: ${errnoMessage(err)}" }
        }
    }

    /**
     * Consumes the eventfd counter to reset it.
     * Called from the EventLoop thread when the eventfd fires.
     */
    private fun consumeWakeup() {
        val err = syscallOps.eventfdWakeupDrain(wakeupFd)
        if (err != 0) {
            logger.debug { "eventfd_read() failed: ${errnoMessage(err)}" }
        }
    }

    // --- Event loop ---

    /**
     * The EventLoop's main loop, running on a dedicated pthread.
     *
     * Each iteration:
     * 1. [drainTasks] — execute queued coroutine continuations
     * 2. `epoll_wait()` — wait for fd readiness events (non-blocking if
     *    tasks are pending, blocking otherwise)
     * 3. Process ready fds — resume associated coroutine continuations
     */
    // --- Idle/read deadline timer (progress-bound mechanism) ---

    private val timeOrigin = TimeSource.Monotonic.markNow()

    private fun nowMillis(): Long = timeOrigin.elapsedNow().inWholeMilliseconds

    /**
     * Per-EventLoop deadline timer backing the transport idle (no-progress) timeout.
     * Confined to this EventLoop thread: transports on this loop schedule / touch /
     * cancel idle deadlines through it, [loop] drives the `epoll_wait` timeout from
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
            val n = syscallOps.waitEvents(epFd, eventBuffer, timeout)
            if (n < 0) {
                // Negative return encodes -errno per EpollSyscallOps contract.
                val err = -n
                // EINTR: interrupted by signal (e.g. debugger attach).
                // EAGAIN: spurious wakeup. Both are retriable.
                if (err == EINTR || err == EAGAIN) continue
                // Fatal error — log and terminate the EventLoop thread.
                // Cannot throw from a pthread; logger is the only output path.
                logger.error { "epoll_wait() fatal error: ${errnoMessage(err)}" }
                break
            }
            for (i in 0 until n) {
                val ev = eventBuffer[i]
                val fd = ev.fd

                if (fd == wakeupFd) {
                    consumeWakeup()
                    continue
                }

                // Process both EPOLLIN and EPOLLOUT if both are set.
                //
                // EPOLLERR / EPOLLHUP are reported by the kernel regardless of
                // the interest mask (man epoll_ctl: "EPOLLERR / EPOLLHUP will
                // always be reported"). On peer FIN / RST the kernel may fire
                // EPOLLHUP without EPOLLIN on this socket — without dispatching
                // these flags as READ ready, the per-fd Pipeline callback
                // never observes the EOF, the read handler never invokes
                // [IoTransport.onReadClosed], the keep-alive loop hangs in
                // its parser, and connections pile up in CLOSE-WAIT. Mapping
                // HUP/ERR onto the READ branch lets the handler call read()
                // which returns 0 and triggers onReadClosed → propagateInactive
                // → bridge close → keep-alive loop exits → finally cleanup.
                val evFlags = ev.events
                val readReady = (evFlags and (EPOLLIN or EPOLLERR or EPOLLHUP)) != 0
                val writeReady = (evFlags and EPOLLOUT) != 0
                // Surface peer-FIN / peer-RST so listeners can fire
                // onReadClosed even when read interest was never armed by user
                // code (`PipelinedChannel.readEnabled = false`). EPOLLRDHUP
                // is the explicit read-side hangup; EPOLLHUP / EPOLLERR are
                // catch-all hangup / error states.
                val eofFlag = (evFlags and (EPOLLHUP or EPOLLERR or EPOLLRDHUP)) != 0
                if (readReady) {
                    dispatchReady(fd, Interest.READ, eofFlag)
                }
                if (writeReady) {
                    // EOF flag also propagates to WRITE dispatch so a write
                    // callback can choose to surface peer-close, but the
                    // primary EOF path is via READ.
                    dispatchReady(fd, Interest.WRITE, eofFlag)
                }
            }
            // Fire any connection idle/read deadlines that elapsed during the wait.
            deadlineScheduler.expireDue(nowMillis())
        }
    }

    /**
     * Computes the `epoll_wait` timeout (ms): `0` if tasks are pending (non-blocking
     * poll), otherwise the time until the nearest connection deadline, or
     * [EpollSyscallOps.TIMEOUT_BLOCK] (-1) to block indefinitely when no deadline is
     * scheduled. A non-positive remaining time clamps to `0` so an already-elapsed
     * deadline is serviced on the next [DeadlineScheduler.expireDue] without blocking.
     */
    private fun computeWaitTimeout(): Int {
        if (hasTasksPending()) return 0
        val next = deadlineScheduler.nextDeadlineMillis()
        if (next == Long.MAX_VALUE) return EpollSyscallOps.TIMEOUT_BLOCK
        val remaining = next - nowMillis()
        return when {
            remaining <= 0L -> 0
            remaining > Int.MAX_VALUE.toLong() -> Int.MAX_VALUE
            else -> remaining.toInt()
        }
    }

    // --- Lifecycle ---

    /**
     * Stops the EventLoop and releases all resources.
     *
     * Signals the EventLoop thread to stop, joins it, then closes the
     * epoll fd and eventfd. Waiters still parked at that point are ended by
     * the loop itself, on its way out.
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
     * **What this does not cover**: a loop that was constructed and never
     * started. `threadPtr` comes from an `Arena`, which does not zero, and
     * `pthread_t` is only a pointer on some targets. On macOS it is one and the
     * slot measures as null, so `t != null` skips the join. On linuxX64 it is a
     * `ULong` — the compiler reports that same check as always true — and the
     * join is handed an uninitialised value whose return is undefined, so
     * neither branch below is reasoning from anything. Production does not
     * reach it (a failed `start()` throws out of the constructor) and the tests
     * that do pass on fresh `malloc` memory reading as zero. Covering it needs
     * an explicit started flag rather than this guard.
     */
    fun close() {
        if (running.compareAndSet(1, 0)) {
            if (threadCreated.value == 0) {
                // No thread, so no drain is coming and nothing below can wait
                // for one. Whatever was dispatched at this loop -- a
                // transport's teardown, an accepted connection -- runs here or
                // never, and the descriptors it holds go with it.
                finishWithoutRunning()
                releaseLoopResources()
                return
            }
            wakeup()
            // Join the EventLoop thread. threadPtr was written by pthread_create.
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
     * had one. Both reach here only after the terminal sequence, so no
     * dispatch and no arm can still be in flight against these.
     */
    private fun releaseLoopResources() {
        closeFdSafely(wakeupFd, logger, "event loop teardown (wakeupFd)")
        closeFdSafely(epFd, logger, "event loop teardown (epFd)")
        // The registration lock is deliberately not destroyed or freed:
        // a cancellation arriving after this point takes it, and those
        // arrive without bound (see AbstractPosixReadinessEventLoop's
        // regMutex). The task queue is lock-free, so it has none either.
        arena.clear()
        // Close the per-EL allocator child. By construction the
        // EventLoopGroup hands each EL the result of
        // `BufferAllocator.createChild()`, so closing here drains
        // this loop's freelists and runs `Freelist.close()` (mutex
        // destroy / nativeHeap.free for `MutexFreelist`). The joined EL
        // thread can no longer allocate — but a returnToPool can still
        // arrive from a post-quiescence closing caller (the stopped-loop
        // transport teardown releases pending writes on its own thread);
        // that race is the allocator's to absorb, via its cross-thread
        // return queue's close-sentinel contract. Default no-op for
        // `DefaultAllocator` (tests that instantiate this loop with the
        // stateless allocator).
        // Free the shared writev scratch arrays — the loop thread is
        // joined above, so no transport flush can touch them anymore.
        nativeHeap.free(writevBases)
        nativeHeap.free(writevLens)
        allocator.close()
    }

    // --- Helpers ---

    /**
     * Adds [newEvents] to the epoll registration for [fd].
     *
     * READ arrives here as `EPOLLIN or EPOLLRDHUP` from [submitArmCallback] and as
     * `EPOLLIN` alone from the suspend path; WRITE as `EPOLLOUT`. Whatever is
     * added has to be taken back by [removeInterest].
     *
     * Uses EPOLL_CTL_ADD for the first registration. If the fd is already
     * registered (EEXIST), falls back to EPOLL_CTL_MOD with the combined events.
     * Skips epoll_ctl entirely when the requested events are already active
     * (e.g., re-arming READ after a Pipeline callback — zero syscall overhead).
     */
    private fun addOrModifyEpoll(fd: Int, newEvents: Int): Int {
        var previous = 0
        val (combined, changed) = withRegLock {
            val current = fdEvents[fd] ?: 0
            previous = current
            val merged = current or newEvents
            fdEvents[fd] = merged
            merged to (merged != current)
        }
        if (!changed) return 0 // same interest already registered — skip epoll_ctl
        var err = syscallOps.epollAdd(epFd, fd, combined)
        if (err == EEXIST) {
            err = syscallOps.epollMod(epFd, fd, combined)
            if (err != 0) {
                logger.debug { "epoll_ctl(MOD, fd=$fd) fallback failed: ${errnoMessage(err)}" }
            }
        } else if (err != 0) {
            // ENOSPC / EBADF / EPERM etc. — unexpected for an fd that
            // was just opened by the engine. Log for diagnostics.
            logger.debug { "epoll_ctl(ADD, fd=$fd) failed: ${errnoMessage(err)}" }
        }
        if (err != 0) {
            // Undo the optimistic bookkeeping: leaving the merged value in
            // place would make a later arm for this fd see no change and skip
            // its epoll_ctl, so one failed arm would silently disable every
            // subsequent one for the same fd.
            withRegLock {
                if (previous == 0) fdEvents.remove(fd) else fdEvents[fd] = previous
            }
        }
        return err
    }

    /**
     * Removes an interest from the epoll registration for [fd], clearing every
     * bit that interest was armed with.
     *
     * Called from [dispatchReady] on both the pipeline path (when a WRITE callback
     * does not re-register, indicating flush success) and the suspend path (when the
     * registration chain empties). Prevents level-triggered busy-loops by removing
     * the interest until the caller arms again.
     *
     * READ clears `EPOLLRDHUP` along with `EPOLLIN` because [submitArmCallback] arms
     * the pair together. Clearing only `EPOLLIN` used to leave `EPOLLRDHUP` armed
     * with nothing able to dispatch it — `loopBody` derives read-readiness from
     * `EPOLLIN|EPOLLERR|EPOLLHUP`, which does not include it — so once the peer sent
     * FIN, level-triggered `epoll_wait` returned that fd on every iteration and the
     * loop spun at 100% until the fd was closed.
     */
    override fun removeInterest(fd: Int, interest: Interest) {
        val removeBit = when (interest) {
            Interest.READ -> EPOLLIN or EPOLLRDHUP
            Interest.WRITE -> EPOLLOUT
        }
        val remaining = withRegLock {
            val current = fdEvents[fd] ?: 0
            val updated = current and removeBit.inv()
            if (updated == 0) {
                fdEvents.remove(fd)
            } else {
                fdEvents[fd] = updated
            }
            updated
        }
        // An empty mask is not the same as being out of the interest list.
        // EPOLLERR / EPOLLHUP are reported whether or not they were asked for,
        // so an fd left registered with 0 events still comes back from every
        // epoll_wait once the peer resets — with its one-shot callback already
        // consumed and no suspend waiter left, that is the no-handler branch of
        // the base's dispatchReady warning on every wake. Drop the fd instead;
        // the next arm re-adds it (addOrModifyEpoll starts with EPOLL_CTL_ADD).
        val err = if (remaining == 0) {
            syscallOps.epollDel(epFd, fd)
        } else {
            syscallOps.epollMod(epFd, fd, remaining)
        }
        if (err != 0) {
            val op = if (remaining == 0) "DEL" else "MOD"
            logger.debug {
                "epoll_ctl($op, fd=$fd, remove ${interest.name}) failed: ${errnoMessage(err)}"
            }
        }
    }

    // --- EpollSuspendRegister impl (seam for connect InProgress) ---

    override suspend fun awaitWriteReady(fd: Int, logger: Logger) = awaitWritableOwningFd(fd, logger)

    companion object {

        /** Initial capacity of the shared writev scratch arrays (grows 1.5x). */
        const val INITIAL_WRITEV_CAPACITY = 8

        /**
         * Maximum events per epoll_wait() call. 64 balances memory usage
         * (64 * sizeof(epoll_event) = ~768 bytes on x86_64) against
         * reducing the number of epoll_wait() syscalls under high fd counts.
         */
        private const val MAX_EVENTS = 64
    }
}
