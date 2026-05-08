package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.IdleReadPolicy
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.StreamServer
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Java NIO [ServerSocketChannel]-based [StreamServer] implementation for JVM.
 *
 * Uses a cached [selectionKey] registered once with `interestOps=0`.
 * Each `accept()` call toggles [SelectionKey.OP_ACCEPT] via
 * [NioEventLoop.setInterestCallback] instead of re-registering.
 *
 * Accepted channels are registered with the next worker EventLoop
 * via [NioEventLoop.registerChannel] (one-time Selector registration),
 * then assigned a cached [SelectionKey] for zero-overhead I/O.
 *
 * ```
 * accept() flow:
 *   bossLoop.setInterestCallback(key, OP_ACCEPT, resumeAllRunnable) → select() → resume
 *   ServerSocketChannel.accept() → client SocketChannel
 *   workerLoop.registerChannel(client) → cached SelectionKey
 *   → NioPipelinedChannel(client, key, transport, workerLoop, ...)
 * ```
 *
 * **Why callback-based**: an earlier design attached the continuation directly
 * via `setInterest(key, ops, cont)`, but `CancellableContinuationImpl`
 * transitively implements [Runnable] (via `DispatchedTask → scheduling.Task`),
 * so [NioEventLoop.processSelectedKeys]' `when (attachment) { is Runnable -> ...;
 * is CancellableContinuation<*> -> ... }` dispatch always took the Runnable
 * branch. The continuation's state never transitioned to `CompletedContinuation`,
 * leaving it installed as a stale child handler on the parent Job. Shutdown
 * then fired `cont.cancel()` which resumed the user state machine a second time
 * and hit a `ClassCastException` in `releaseIntercepted`. Attaching a plain
 * `Runnable { cont.resume(Unit) }` keeps the continuation off the SelectionKey
 * and routes through `CancellableContinuationImpl.resume`'s CAS state machine
 * instead.
 *
 * **Multi-waiter accept**: multiple coroutines may call [accept] concurrently —
 * each enqueues into [pendingAcceptConts] (FIFO) and the shared
 * [resumeAllRunnable] attached to [selectionKey] resumes the entire queue when
 * `OP_ACCEPT` fires. POSIX `accept()` is thread-safe; the resumed waiters race
 * for connections via their own retry loop, and any waiter that observes
 * `EAGAIN` re-enters the slow path and re-arms the interest callback. Previously
 * the design held a single `pendingAcceptCont` slot AND attached a fresh
 * `cont`-bound Runnable per waiter; concurrent callers overwrote both,
 * silently leaking continuations.
 *
 * @param serverChannel The listening ServerSocketChannel (non-blocking).
 * @param selectionKey  Cached SelectionKey registered with the boss Selector.
 * @param bossLoop      EventLoop for accept readiness notification.
 * @param workerGroup   Worker EventLoopGroup for accepted channels.
 * @param localAddress  Bind address of this server channel.
 */
internal class NioStreamServer(
    private val serverChannel: ServerSocketChannel,
    private val selectionKey: SelectionKey,
    private val bossLoop: NioEventLoop,
    private val workerGroup: NioEventLoopGroup,
    override val localAddress: SocketAddress,
    private val bindConfig: BindConfig,
    private val idleReadPolicy: IdleReadPolicy,
    private val logger: Logger = io.github.fukusaka.keel.logging.NoopLoggerFactory.logger("NioStreamServer"),
) : StreamServer {

    // State transitions (_active, pendingAcceptConts) may be observed
    // from the boss EventLoop thread (accept readiness callback) and
    // from arbitrary coroutine dispatcher threads (accept() / close()
    // callers), so all reads/writes go through synchronized(lock).
    // @Volatile on _active lets isActive read without entering the lock.
    private val lock = Any()

    @Volatile
    private var _active = true

    // FIFO queue of suspended accept() callers. The previous single-slot
    // design (`pendingAcceptCont: CancellableContinuation<Unit>?`) plus
    // a per-waiter Runnable bound to the SelectionKey via `key.attach`
    // silently lost continuations on every level: two concurrent
    // `accept()` calls overwrote each other in `pendingAcceptCont` AND
    // overwrote each other's Runnable on the SelectionKey. Counterpart
    // of the POSIX engines' chain (PR #367), the io-uring queue
    // (PR #368), the Netty queue (PR #369), the Node.js queue (PR #370),
    // and the NWConnection queue (PR #371). Identity-based
    // `ArrayDeque.remove(cont)` works because `CancellableContinuation`
    // inherits `Object.equals` (reference identity).
    private val pendingAcceptConts = ArrayDeque<CancellableContinuation<Unit>>()

    /**
     * Single shared [Runnable] attached to [selectionKey] when waiters
     * are queued. On `OP_ACCEPT` fire, [NioEventLoop.processSelectedKeys]
     * clears the attachment and runs this — we resume every queued
     * waiter so they all race to retry `accept()`. POSIX `accept()` is
     * thread-safe; the kernel disperses queued connections to whichever
     * waiter's syscall lands first, and waiters that observe `EAGAIN`
     * re-enter the slow path and re-arm the interest callback for the
     * next fire.
     *
     * Resume-all (vs resume-one + re-arm) is intentional: accept is
     * control-plane (per-TCP-setup, low rate), so the brief
     * "thundering herd" cost is dominated by the syscall serialisation
     * in the kernel anyway, and it avoids a recursive
     * `setInterestCallback` call chain inside the EventLoop callback.
     */
    private val resumeAllRunnable = Runnable {
        val toResume = synchronized(lock) {
            if (pendingAcceptConts.isEmpty()) {
                emptyList()
            } else {
                val list = pendingAcceptConts.toList()
                pendingAcceptConts.clear()
                list
            }
        }
        for (cont in toResume) cont.resume(Unit)
    }

    override val isActive: Boolean get() = _active

    /**
     * Suspends until an incoming connection arrives, then returns a [NioPipelinedChannel]
     * assigned to the next worker EventLoop with a cached [SelectionKey].
     */
    override suspend fun accept(): PipelinedChannel {
        check(_active) { "StreamServer is closed" }

        while (true) {
            val client = serverChannel.accept()
            if (client != null) {
                client.configureBlocking(false)
                applySocketOptions(client, bindConfig.childSocketOptions)
                val remoteAddr = NioPipelinedChannel.toSocketAddress(client.remoteAddress)
                val localAddr = NioPipelinedChannel.toSocketAddress(client.localAddress)
                val workerLoop = workerGroup.next()
                // One-time registration with the worker's Selector.
                // Returns a cached SelectionKey for interestOps toggling.
                val clientKey = workerLoop.registerChannel(client)
                val transport = NioIoTransport(
                    client,
                    clientKey,
                    workerLoop,
                    workerLoop.allocator,
                    idleReadPolicy,
                )
                val channel = NioPipelinedChannel(transport, logger, remoteAddr, localAddr)
                bindConfig.initializeConnection(channel)
                return channel
            }

            suspendCancellableCoroutine<Unit> { cont ->
                val closedAlready = synchronized(lock) {
                    if (!_active) {
                        true
                    } else {
                        pendingAcceptConts.addLast(cont)
                        false
                    }
                }
                if (closedAlready) {
                    cont.resumeWithException(CancellationException("StreamServer closed"))
                    return@suspendCancellableCoroutine
                }
                // Re-arm OP_ACCEPT with the shared resumeAll Runnable.
                // Multiple concurrent accept callers all call this; the
                // attachment is the same instance so `key.attach` is
                // effectively idempotent. interestOps OR with OP_ACCEPT
                // is also idempotent.
                bossLoop.setInterestCallback(selectionKey, SelectionKey.OP_ACCEPT, resumeAllRunnable)
                cont.invokeOnCancellation {
                    // Identity-based remove via CancellableContinuation's
                    // default Object.equals (reference equality). Do not
                    // call removeInterest here — siblings may still be
                    // queued. The Runnable handles an empty queue
                    // gracefully (no-op) so leaving the interest armed is
                    // safe.
                    synchronized(lock) { pendingAcceptConts.remove(cont) }
                }
            }
            // Loop back and retry accept().
        }
    }

    /**
     * Closes the server channel and stops accepting connections.
     *
     * Idempotent: subsequent calls are no-ops. Every queued [accept]
     * coroutine is resumed with [CancellationException].
     *
     * **Thread safety**: safe to call from any thread. [_active] /
     * [pendingAcceptConts] transitions are serialised under [lock];
     * [SelectionKey.cancel] and [ServerSocketChannel.close] are
     * thread-safe per JDK `java.nio.channels` contract.
     */
    override fun close() {
        val toCancel = synchronized(lock) {
            if (!_active) return
            _active = false
            val list = pendingAcceptConts.toList()
            pendingAcceptConts.clear()
            list
        }
        for (cont in toCancel) {
            cont.resumeWithException(CancellationException("StreamServer closed"))
        }
        selectionKey.cancel()
        serverChannel.close()
    }
}
