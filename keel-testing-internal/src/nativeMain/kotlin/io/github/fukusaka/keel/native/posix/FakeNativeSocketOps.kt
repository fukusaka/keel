package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.core.Host
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.SocketOption
import io.github.fukusaka.keel.core.UnixSocketAddress

/**
 * Test-only in-memory [NativeSocketOps] implementation.
 *
 * Sibling of [FakeNativeSocket] (hot-path seam fake). Where
 * [FakeNativeSocket] scripts per-syscall responses keyed by `fd`,
 * [FakeNativeSocketOps] scripts lifecycle responses — fd allocation
 * for `bindListener` / `openClientSocket` / UDS variants,
 * [ConnectResult] for `connect*NonBlocking`, [Int] for
 * `getSocketError`, and addresses for `getLocalAddress` /
 * `getRemoteAddress`.
 *
 * ## Model
 *
 * Two modes for each method:
 *
 * - **Scripted queue** (FIFO via `enqueue*`): test enqueues the
 *   exact sequence of responses the system under test should
 *   observe.
 * - **Counter fallback** (`nextCreatedFd` / `defaultConnect` /
 *   `defaultSocketError` / `defaultAddresses`): consulted when the
 *   queue is empty. `nextCreatedFd` auto-increments so a test that
 *   doesn't care about fd values still gets distinct integers.
 *
 * Call tracking mirrors [FakeNativeSocket] — per-method counters +
 * an ordered [createdFds] list (records every fd the fake handed
 * out) plus [nonBlockingFds] (records `setNonBlocking` invocations,
 * useful for asserting the server's accepted-client chain ran
 * non-blocking before address reads).
 *
 * ## Thread safety
 *
 * Single-threaded only. Engine tests normally drive the system
 * under test on a single coroutine; if cross-thread access is
 * needed, wrap in `synchronized` at the call site.
 *
 * ## What the fake does NOT do
 *
 * - **No kernel state**: `bindListener` etc. do not actually
 *   bind anything; the returned fd is just an int the test can
 *   later assert on.
 * - **No argument capture**: `port` / `backlog` / `address` /
 *   `logger` / `reusePort` are consumed but not recorded. Tests
 *   cannot assert that the correct port or backlog was passed.
 *   Compose a wrapper if capture is needed.
 * - **No NativeSocket coordination**: `connectNonBlocking` does NOT
 *   delegate to [NativeSocket.connect] like the real impl does.
 *   The scripted [ConnectResult] is returned directly.
 */
public class FakeNativeSocketOps : NativeSocketOps {

    // --- Scripted queues (FIFO) ---

    /**
     * Response type for `bindListener` / `openClientSocket` family
     * methods. [Fd] returns the fd as the method's normal return
     * value; [Throws] throws the supplied exception from the method
     * body, emulating production failure paths like socket EMFILE /
     * bind EADDRINUSE.
     */
    private sealed class FdResponse {
        data class Fd(val fd: Int) : FdResponse()
        data class Throws(val exception: Throwable) : FdResponse()
    }

    private val bindListenerQueue = ArrayDeque<FdResponse>()
    private val bindListenerReusePortQueue = ArrayDeque<FdResponse>()
    private val bindUnixListenerQueue = ArrayDeque<FdResponse>()
    private val openClientSocketQueue = ArrayDeque<FdResponse>()
    private val openUnixClientSocketQueue = ArrayDeque<FdResponse>()
    private val connectQueue = mutableMapOf<Int, ArrayDeque<ConnectResult>>()
    private val connectUnixQueue = mutableMapOf<Int, ArrayDeque<ConnectResult>>()
    private val socketErrorQueue = mutableMapOf<Int, ArrayDeque<Int>>()
    private val localAddressQueue = mutableMapOf<Int, ArrayDeque<SocketAddress>>()
    private val remoteAddressQueue = mutableMapOf<Int, ArrayDeque<SocketAddress>>()

    // --- Defaults / counters ---

    /** Seed for auto-incrementing fd allocation on unscripted `bindListener` / `openClientSocket` calls. */
    public var nextCreatedFd: Int = 100

    /** Returned by [connectNonBlocking] / [connectUnixNonBlocking] when no queue entry is scripted for the fd. */
    public var defaultConnect: ConnectResult = ConnectResult.InProgress

    /** Returned by [getSocketError] when no queue entry is scripted for the fd. */
    public var defaultSocketError: Int = 0

    /**
     * Returned by [getLocalAddress] / [getRemoteAddress] when the
     * per-fd queue is empty. `.first` is treated as remote, `.second`
     * as local.
     */
    public var defaultAddresses: Pair<SocketAddress, SocketAddress> =
        InetSocketAddress(Host.Ip(IpAddress.V4.ANY), 0) to InetSocketAddress(Host.Ip(IpAddress.V4.ANY), 0)

    // --- Call tracking ---

    public var bindListenerCalls: Int = 0
        private set
    public var bindListenerReusePortCalls: Int = 0
        private set
    public var bindUnixListenerCalls: Int = 0
        private set
    public var openClientSocketCalls: Int = 0
        private set
    public var openUnixClientSocketCalls: Int = 0
        private set
    public var connectCalls: Int = 0
        private set
    public var connectUnixCalls: Int = 0
        private set
    public var getSocketErrorCalls: Int = 0
        private set
    public var getLocalAddressCalls: Int = 0
        private set
    public var getRemoteAddressCalls: Int = 0
        private set
    public var setNonBlockingCalls: Int = 0
        private set
    public var setSocketOptionCalls: Int = 0
        private set

    private val _createdFds = mutableListOf<Int>()
    private val _nonBlockingFds = mutableListOf<Int>()
    private val _appliedOptions = mutableListOf<Pair<Int, SocketOption>>()

    /** Ordered list of fds returned by any `bindListener` / `openClientSocket` / UDS variant. */
    public val createdFds: List<Int> get() = _createdFds.toList()

    /** Ordered list of fds passed to [setNonBlocking]. */
    public val nonBlockingFds: List<Int> get() = _nonBlockingFds.toList()

    /**
     * Ordered list of `(fd, option)` pairs passed to
     * [setSocketOption]. Use to assert the exact sequence of option
     * applications (e.g., `assertEquals(listOf(fd to TcpNoDelay(true),
     * fd to KeepAlive(true)), fake.appliedOptions)`).
     */
    public val appliedOptions: List<Pair<Int, SocketOption>> get() = _appliedOptions.toList()

    private fun allocateFd(queue: ArrayDeque<FdResponse>): Int {
        val fd = when (val r = queue.removeFirstOrNull()) {
            null -> nextCreatedFd++
            is FdResponse.Fd -> r.fd
            is FdResponse.Throws -> throw r.exception
        }
        _createdFds.add(fd)
        return fd
    }

    // --- NativeSocketOps impl ---

    override fun bindListener(
        address: IpAddress,
        port: Int,
        backlog: Int,
        reusePort: Boolean,
    ): Int {
        if (reusePort) {
            bindListenerReusePortCalls++
            return allocateFd(bindListenerReusePortQueue)
        }
        bindListenerCalls++
        return allocateFd(bindListenerQueue)
    }

    override fun openClientSocket(family: IpAddress): Int {
        openClientSocketCalls++
        return allocateFd(openClientSocketQueue)
    }

    override fun connectNonBlocking(fd: Int, address: IpAddress, port: Int): ConnectResult {
        connectCalls++
        return connectQueue[fd]?.removeFirstOrNull() ?: defaultConnect
    }

    /**
     * Thrown by the next [getSocketError], then cleared.
     *
     * `getsockopt(SO_ERROR)` is a `check` over the syscall in the real ops, and
     * it is read on the in-progress connect path once write-readiness arrives —
     * with the descriptor back in the caller's hands and nobody else holding it.
     */
    public var getSocketErrorThrowsOnce: Throwable? = null

    override fun getSocketError(fd: Int): Int {
        getSocketErrorCalls++
        getSocketErrorThrowsOnce?.let {
            getSocketErrorThrowsOnce = null
            throw it
        }
        return socketErrorQueue[fd]?.removeFirstOrNull() ?: defaultSocketError
    }

    /**
     * Thrown by the next [getLocalAddress], then cleared.
     *
     * `getsockname` is a `check` over the syscall in the real ops, and it is
     * the one step inside a `bind` guard that can fail — the listener fd is
     * open by then and nothing else holds it until the server is built.
     */
    public var getLocalAddressThrowsOnce: Throwable? = null

    override fun getLocalAddress(fd: Int): SocketAddress {
        getLocalAddressCalls++
        getLocalAddressThrowsOnce?.let {
            getLocalAddressThrowsOnce = null
            throw it
        }
        return localAddressQueue[fd]?.removeFirstOrNull() ?: defaultAddresses.second
    }

    /**
     * Thrown by the next [getRemoteAddress], then cleared.
     *
     * `getpeername` is a `check` over the syscall in the real ops, and a peer
     * that resets between the connection completing and the query answers
     * `ENOTCONN` — the one failure in the accept and connect construction
     * windows that is reachable rather than defensive. Consumed once so a test
     * can assert the seam reached it.
     */
    public var getRemoteAddressThrowsOnce: Throwable? = null

    override fun getRemoteAddress(fd: Int): SocketAddress {
        getRemoteAddressCalls++
        getRemoteAddressThrowsOnce?.let {
            getRemoteAddressThrowsOnce = null
            throw it
        }
        return remoteAddressQueue[fd]?.removeFirstOrNull() ?: defaultAddresses.first
    }

    /**
     * Makes the next [setNonBlocking] throw this, then clears itself.
     *
     * The production `PosixNativeSocketOps` reaches `setNonBlocking` through
     * `check(...)` over `fcntl`, so a single accepted socket whose descriptor
     * cannot be made non-blocking throws — on the accept loop's own thread.
     * One-shot rather than sticky so a test can assert that the *next*
     * connection is still served.
     */
    public var setNonBlockingThrowsOnce: Throwable? = null

    override fun setNonBlocking(fd: Int) {
        setNonBlockingCalls++
        // Recorded only on the way out: [nonBlockingFds] is what tests read to
        // decide which descriptors were made non-blocking, and a throwing call
        // did not make this one non-blocking. Listing it anyway would let a
        // test asserting the fd was prepared pass against a build where it
        // never was.
        setNonBlockingThrowsOnce?.let {
            setNonBlockingThrowsOnce = null
            throw it
        }
        _nonBlockingFds.add(fd)
    }

    override fun setSocketOption(fd: Int, option: SocketOption) {
        setSocketOptionCalls++
        _appliedOptions.add(fd to option)
    }

    override fun bindUnixListener(address: UnixSocketAddress, backlog: Int): Int {
        bindUnixListenerCalls++
        return allocateFd(bindUnixListenerQueue)
    }

    override fun openUnixClientSocket(): Int {
        openUnixClientSocketCalls++
        return allocateFd(openUnixClientSocketQueue)
    }

    override fun connectUnixNonBlocking(fd: Int, address: UnixSocketAddress): ConnectResult {
        connectUnixCalls++
        return connectUnixQueue[fd]?.removeFirstOrNull() ?: defaultConnect
    }

    // --- Script setup ---

    /** Appends fds the fake will hand out from `bindListener(reusePort = false)`. */
    public fun enqueueBindListener(vararg fds: Int) {
        bindListenerQueue.addAll(fds.map { FdResponse.Fd(it) })
    }

    /**
     * Appends scripted exceptions thrown from `bindListener(reusePort = false)`.
     * Emulates production failures like socket EMFILE or bind EADDRINUSE.
     * Ordering is FIFO and interleaved with [enqueueBindListener] —
     * the first enqueued response fires first regardless of type.
     */
    public fun enqueueBindListenerThrows(vararg exceptions: Throwable) {
        bindListenerQueue.addAll(exceptions.map { FdResponse.Throws(it) })
    }

    /** Appends fds the fake will hand out from `bindListener(reusePort = true)`. */
    public fun enqueueBindListenerReusePort(vararg fds: Int) {
        bindListenerReusePortQueue.addAll(fds.map { FdResponse.Fd(it) })
    }

    /** Appends scripted exceptions for `bindListener(reusePort = true)`. */
    public fun enqueueBindListenerReusePortThrows(vararg exceptions: Throwable) {
        bindListenerReusePortQueue.addAll(exceptions.map { FdResponse.Throws(it) })
    }

    /** Appends fds the fake will hand out from `bindUnixListener`. */
    public fun enqueueBindUnixListener(vararg fds: Int) {
        bindUnixListenerQueue.addAll(fds.map { FdResponse.Fd(it) })
    }

    /** Appends scripted exceptions for `bindUnixListener`. */
    public fun enqueueBindUnixListenerThrows(vararg exceptions: Throwable) {
        bindUnixListenerQueue.addAll(exceptions.map { FdResponse.Throws(it) })
    }

    /** Appends fds the fake will hand out from `openClientSocket`. */
    public fun enqueueOpenClientSocket(vararg fds: Int) {
        openClientSocketQueue.addAll(fds.map { FdResponse.Fd(it) })
    }

    /**
     * Appends scripted exceptions for `openClientSocket`.
     * Emulates production failures like socket EMFILE.
     */
    public fun enqueueOpenClientSocketThrows(vararg exceptions: Throwable) {
        openClientSocketQueue.addAll(exceptions.map { FdResponse.Throws(it) })
    }

    /** Appends fds the fake will hand out from `openUnixClientSocket`. */
    public fun enqueueOpenUnixClientSocket(vararg fds: Int) {
        openUnixClientSocketQueue.addAll(fds.map { FdResponse.Fd(it) })
    }

    /** Appends scripted exceptions for `openUnixClientSocket`. */
    public fun enqueueOpenUnixClientSocketThrows(vararg exceptions: Throwable) {
        openUnixClientSocketQueue.addAll(exceptions.map { FdResponse.Throws(it) })
    }

    /** Appends scripted [ConnectResult] responses for `connectNonBlocking(fd, ...)`. */
    public fun enqueueConnect(fd: Int, vararg results: ConnectResult) {
        connectQueue.getOrPut(fd) { ArrayDeque() }.addAll(results)
    }

    /** Appends scripted [ConnectResult] responses for `connectUnixNonBlocking(fd, ...)`. */
    public fun enqueueConnectUnix(fd: Int, vararg results: ConnectResult) {
        connectUnixQueue.getOrPut(fd) { ArrayDeque() }.addAll(results)
    }

    /** Appends scripted errno values for `getSocketError(fd)`. */
    public fun enqueueSocketError(fd: Int, vararg errors: Int) {
        socketErrorQueue.getOrPut(fd) { ArrayDeque() }.addAll(errors.toList())
    }

    /** Appends scripted addresses for `getLocalAddress(fd)`. */
    public fun enqueueLocalAddress(fd: Int, vararg addresses: SocketAddress) {
        localAddressQueue.getOrPut(fd) { ArrayDeque() }.addAll(addresses)
    }

    /** Appends scripted addresses for `getRemoteAddress(fd)`. */
    public fun enqueueRemoteAddress(fd: Int, vararg addresses: SocketAddress) {
        remoteAddressQueue.getOrPut(fd) { ArrayDeque() }.addAll(addresses)
    }

    // --- Assertion helper ---

    /**
     * Throws if any scripted response queue is non-empty. Call at
     * the end of a test to verify every queued response was
     * consumed — an unconsumed response usually means the system
     * under test short-circuited before reaching that branch.
     */
    public fun assertAllConsumed() {
        val leftovers = buildList {
            if (bindListenerQueue.isNotEmpty()) add("bindListener: ${bindListenerQueue.size} remaining")
            if (bindListenerReusePortQueue.isNotEmpty()) {
                add("bindListener(reusePort): ${bindListenerReusePortQueue.size} remaining")
            }
            if (bindUnixListenerQueue.isNotEmpty()) {
                add("bindUnixListener: ${bindUnixListenerQueue.size} remaining")
            }
            if (openClientSocketQueue.isNotEmpty()) {
                add("openClientSocket: ${openClientSocketQueue.size} remaining")
            }
            if (openUnixClientSocketQueue.isNotEmpty()) {
                add("openUnixClientSocket: ${openUnixClientSocketQueue.size} remaining")
            }
            fun report(name: String, queues: Map<Int, ArrayDeque<*>>) {
                for ((fd, q) in queues) {
                    if (q.isNotEmpty()) add("$name(fd=$fd): ${q.size} remaining")
                }
            }
            report("connect", connectQueue)
            report("connectUnix", connectUnixQueue)
            report("socketError", socketErrorQueue)
            report("localAddress", localAddressQueue)
            report("remoteAddress", remoteAddressQueue)
        }
        check(leftovers.isEmpty()) { "unconsumed scripted responses: $leftovers" }
    }
}
