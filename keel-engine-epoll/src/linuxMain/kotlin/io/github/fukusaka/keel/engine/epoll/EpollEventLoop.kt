package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.logging.error
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.native.posix.errnoMessage
import io.github.fukusaka.keel.native.readiness.AbstractReadinessEventLoop
import io.github.fukusaka.keel.native.readiness.FdReadyListener
import io.github.fukusaka.keel.native.readiness.Interest
import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import io.github.fukusaka.keel.native.readiness.ReadinessEventLoopLifecycle
import io.github.fukusaka.keel.native.readiness.ReadinessIoTransport
import io.github.fukusaka.keel.native.readiness.ReadinessSuspendRegister
import io.github.fukusaka.keel.pipeline.DeadlineScheduler
import io.github.fukusaka.keel.pipeline.IoTransport
import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
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
@OptIn(ExperimentalForeignApi::class, InternalReadinessEngineApi::class)
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
    override val allocator: BufferAllocator = DefaultAllocator,
    /**
     * Engine-wide default read buffer size
     * ([io.github.fukusaka.keel.core.IoEngineConfig.readBufferSize]) for
     * connections on this loop. Used as the fallback when a connection's
     * [io.github.fukusaka.keel.core.BindConfig.readBufferSize] /
     * [io.github.fukusaka.keel.core.ConnectConfig.readBufferSize] is `null`;
     * the effective size is captured per connection on the transport.
     */
    override val readBufferSize: Int = IoTransport.DEFAULT_READ_BUFFER_SIZE,
    /**
     * Engine-wide default idle (no-progress) timeout in milliseconds
     * ([io.github.fukusaka.keel.core.IoEngineConfig.idleTimeoutMillis]) for
     * connections on this loop (`0` = disabled). Fallback when a connection's
     * [io.github.fukusaka.keel.core.BindConfig.idleTimeoutMillis] /
     * [io.github.fukusaka.keel.core.ConnectConfig.idleTimeoutMillis] is `null`.
     */
    override val idleTimeoutMillis: Long = 0,
    /**
     * Engine-wide [io.github.fukusaka.keel.core.IoEngineConfig.flushCoalescing]
     * value. When `true` (default), [ReadinessIoTransport.flush] schedules the
     * actual send onto the next EL tick via [dispatch] so that same-tick
     * per-emit `requestFlush` calls collapse into one `writev(2)`. When
     * `false`, each `flush()` sends immediately (pre-#900 behaviour).
     */
    override val flushCoalescing: Boolean = true,
    private val syscallOps: EpollSyscallOps = PosixEpollSyscallOps,
) : AbstractReadinessEventLoop(), ReadinessSuspendRegister, ReadinessEventLoopLifecycle {

    /**
     * The epoll file descriptor, created at construction.
     * Used by this loop's own syscalls; nothing outside reads it. Server and
     * channel fds both reach the kernel through the loop's registration path,
     * never by naming this.
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

    private val wakeupFd: Int
    private val running = AtomicInt(1) // 1 = running, 0 = stopped

    // Whether `pthread_create` has been called for this loop and is not
    // known to have failed. Deliberately not "succeeded": it is set before the
    // call (see there), so between the two it is ahead of reality. A loop can be
    // closed without one: a group whose `start()` fails part way leaves the
    // rest of it constructed and idle, and tests build loops they never
    // start. There is nothing to join in that case, and nothing that will
    // ever run the teardown -- so `close()` runs it here instead.
    private val threadCreated = AtomicInt(0)

    /**
     * Backs [threadPtr]. Cleared by [releaseConstructionScratch], from [close]
     * after the loop's terminal sequence has run — by the joined thread, or by
     * the closing one when there was none — or from the constructor's own
     * unwind when the loop never came to exist. Not cleared at all when that
     * sequence is still running and [close] refuses to release — which includes
     * this very thread, re-entered.
     */
    private val arena = Arena()

    private val threadPtr = arena.alloc<pthread_tVar>()

    // Each stage below releases what it took, and only that: a failure unwinds
    // through the stages behind it, each closing its own. Nothing else can --
    // a constructor that throws hands out no reference, so [close] is
    // unreachable for the rest of the process, and the scratch and
    // descriptors would be held until it exits.
    //
    // The property initialisers above are outside this, and an allocation
    // failing in any of them leaves the ones before it with no unwind at all.
    // That is left as it is because a `nativeHeap` allocation of a few dozen
    // bytes failing is not a condition this process continues past. The gather
    // scratch used to be among them; it belongs to the shared loop base now,
    // which pairs its two allocations rather than relying on that.
    //
    // The catches are not decoration on this engine either: every syscall here
    // reports by errno, but each report is raised as an `error(...)` inside the
    // try, so the catches are where both descriptors go back -- the only place.
    // What the kqueue loop has and this one does not is a stage that throws
    // without being asked to: there the wakeup fds are made non-blocking by an
    // op whose contract is to throw.
    init {
        try {
            val fd = syscallOps.epollCreate()
            if (fd < 0) error("epoll_create1() failed: ${errnoMessage(-fd)}")
            epFd = fd

            try {
                // Create eventfd for wakeup and register with epoll.
                // eventfd is more efficient than pipe on Linux: single fd,
                // kernel-optimized for event signaling.
                val wf = syscallOps.eventfdCreate()
                if (wf < 0) error("eventfd() failed: ${errnoMessage(-wf)}")
                wakeupFd = wf

                try {
                    val ctlErr = syscallOps.epollAdd(epFd, wakeupFd, EPOLLIN)
                    if (ctlErr != 0) error("epoll_ctl(ADD, wakeupFd) failed: ${errnoMessage(ctlErr)}")
                } catch (wakeupSetupFailure: Throwable) {
                    closeFdSafely(wakeupFd, logger, "epoll init (wakeup setup failure)")
                    throw wakeupSetupFailure
                }
            } catch (wakeupFailure: Throwable) {
                closeFdSafely(epFd, logger, "epoll init (wakeup failure)")
                throw wakeupFailure
            }
        } catch (constructionFailure: Throwable) {
            releaseOnConstructionFailure(constructionFailure)
            throw constructionFailure
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
    override fun start() {
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
     * stated at the arm itself in `ReadinessIoTransport.init`.
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
    override fun cleanupFd(fd: Int) {
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
     * A loop with a thread is signalled and joined; one that never started is
     * taken apart here. Then the epoll fd and eventfd are closed. Waiters still
     * parked at that point are ended by the loop itself on its way out, or by
     * this thread when there was no loop; see below.
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
     * `pthread_t` is only a pointer on some targets — on linuxX64 it is a
     * `ULong` and the compiler reports the `t != null` guard below as always
     * true. The flag keeps that guard off the never-started path entirely, so
     * it is only asked about a slot `pthread_create` was called for — which is
     * not quite "written": the flag is set just before that call, and the
     * comment on it describes the window that leaves open.
     */
    override fun close() {
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
                        "${this::class.simpleName}.close() found the loop still being taken apart -- " +
                            "possibly by this thread, re-entered -- so it is releasing nothing, " +
                            "because releasing is what would corrupt"
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
        closeFdSafely(wakeupFd, logger, "event loop teardown (wakeupFd)")
        closeFdSafely(epFd, logger, "event loop teardown (epFd)")
        // The registration lock is deliberately not destroyed or freed:
        // a cancellation arriving after this point takes it, and those
        // arrive without bound (see AbstractReadinessEventLoop's
        // regMutex). The task queue is lock-free, so it has none either.
        //
        // The arena and the writev scratch go together, and what makes that
        // safe on every route here is quiescence, not a join: the thread may
        // be joined, may never have existed, or may be another caller's that
        // ran the sequence and is not joined at all. In each, the sequence has
        // finished, so no transport flush can be in it.
        releaseConstructionScratch()
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
        allocator.close()
    }

    /**
     * Gives back what the constructor took, without letting the giving back
     * replace the reason it is happening.
     *
     * The two obligations below are attempted independently — the second runs
     * whatever the first did — and a failure is suppressed onto [cause] rather
     * than thrown: the caller is owed the failure that ended the construction,
     * not one from the cleanup after it. `close()` on the allocator is the only
     * one here that can realistically throw, since [BufferAllocator] is a public
     * interface implementable outside this project and nothing wraps it. The
     * releases the constructor makes above are reported through a logger the
     * engine has already wrapped — every loop is handed one from a factory the
     * engine guards — so a throwing [io.github.fukusaka.keel.logging.Logger]
     * cannot reach them.
     *
     * The shape is the transport's, whose teardown runs each stage this way.
     * [releaseLoopResources] does not: it calls the same two straight, so a
     * throw from the first would skip the second. That is how it was before
     * this, and changing what a teardown does with a failing release is a
     * decision about the teardown rather than about construction.
     *
     * The allocator is the child the group carved for this loop, and the loop
     * is what closes it — [releaseLoopResources] ends by doing so. The parent's
     * cascade is not a substitute in this case: an engine whose construction
     * failed is discarded, so nothing closes the parent either. `close()` is
     * idempotent, so a cascade that does reach the child later is a no-op.
     */
    private fun releaseOnConstructionFailure(cause: Throwable) {
        try {
            releaseConstructionScratch()
        } catch (scratchFailure: Throwable) {
            cause.addSuppressed(scratchFailure)
        }
        try {
            allocator.close()
        } catch (allocatorFailure: Throwable) {
            cause.addSuppressed(allocatorFailure)
        }
    }

    /**
     * Frees the native memory this loop takes before it owns any descriptor:
     * the arena behind [threadPtr] and the shared writev scratch.
     *
     * Called from both ends of the loop's life — [releaseLoopResources] for
     * one that ran, and [releaseOnConstructionFailure] for one that never came
     * to exist. Kept as a single function so a third allocation cannot be
     * released along one of those and forgotten along the other.
     *
     * The two releases are not staged against each other the way
     * [releaseOnConstructionFailure] stages its two: none of them can fail
     * short of a corrupt heap, and a `nativeHeap` this process cannot free is
     * not one it continues past.
     *
     * The caller establishes that nothing can still be using them. It does
     * not need saying for the constructor: no reference to a loop whose
     * `init` threw ever leaves it.
     */
    private fun releaseConstructionScratch() {
        arena.clear()
        freeWritevScratch()
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

    // --- ReadinessSuspendRegister impl (seam for connect InProgress) ---

    override suspend fun awaitWriteReady(fd: Int, logger: Logger) = awaitWritableOwningFd(fd, logger)

    companion object {

        /**
         * Maximum events per epoll_wait() call. 64 balances memory usage
         * (64 * sizeof(epoll_event) = ~768 bytes on x86_64) against
         * reducing the number of epoll_wait() syscalls under high fd counts.
         */
        private const val MAX_EVENTS = 64
    }
}
