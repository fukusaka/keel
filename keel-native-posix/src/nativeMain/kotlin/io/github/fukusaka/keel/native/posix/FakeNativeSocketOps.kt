package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.core.Host
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.logging.Logger

/**
 * Test-only in-memory [NativeSocketOps] implementation.
 *
 * Sibling of [FakeNativeSocket] (hot-path seam fake). Where
 * [FakeNativeSocket] scripts per-syscall responses keyed by `fd`,
 * [FakeNativeSocketOps] scripts lifecycle responses — fd allocation
 * for `create*Socket*`, [ConnectResult] for `connect*NonBlocking`,
 * [Int] for `getSocketError`, and `(remote, local)` pairs for
 * `acceptClient` / `getLocalAddress` / `getRemoteAddress`.
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
 * useful for asserting the `acceptClient` chain ran non-blocking
 * before address reads).
 *
 * ## Thread safety
 *
 * Single-threaded only. Engine tests normally drive the system
 * under test on a single coroutine; if cross-thread access is
 * needed, wrap in `synchronized` at the call site.
 *
 * ## What the fake does NOT do
 *
 * - **No kernel state**: `createServerSocket` etc. do not actually
 *   bind anything; the returned fd is just an int the test can
 *   later assert on.
 * - **No argument capture**: `port` / `backlog` / `address` /
 *   `logger` are consumed but not recorded. Tests cannot assert
 *   that the correct port or backlog was passed. Compose a wrapper
 *   if capture is needed — base class stays argument-less.
 * - **No NativeSocket coordination**: `connectNonBlocking` does NOT
 *   delegate to [NativeSocket.connect] like the real impl does.
 *   The scripted [ConnectResult] is returned directly.
 */
@InternalTestApi
public class FakeNativeSocketOps : NativeSocketOps {

    // --- Scripted queues (FIFO) ---

    private val createServerSocketQueue = ArrayDeque<Int>()
    private val createReusePortServerSocketQueue = ArrayDeque<Int>()
    private val createUnixServerSocketQueue = ArrayDeque<Int>()
    private val createUnconnectedSocketQueue = ArrayDeque<Int>()
    private val createUnixUnconnectedSocketQueue = ArrayDeque<Int>()
    private val connectQueue = mutableMapOf<Int, ArrayDeque<ConnectResult>>()
    private val connectUnixQueue = mutableMapOf<Int, ArrayDeque<ConnectResult>>()
    private val socketErrorQueue = mutableMapOf<Int, ArrayDeque<Int>>()
    private val localAddressQueue = mutableMapOf<Int, ArrayDeque<SocketAddress>>()
    private val remoteAddressQueue = mutableMapOf<Int, ArrayDeque<SocketAddress>>()
    private val acceptClientQueue = mutableMapOf<Int, ArrayDeque<Pair<SocketAddress, SocketAddress>>>()

    // --- Defaults / counters ---

    /** Seed for auto-incrementing fd allocation on unscripted create* calls. */
    public var nextCreatedFd: Int = 100

    /** Returned by [connectNonBlocking] / [connectUnixNonBlocking] when no queue entry is scripted for the fd. */
    public var defaultConnect: ConnectResult = ConnectResult.InProgress

    /** Returned by [getSocketError] when no queue entry is scripted for the fd. */
    public var defaultSocketError: Int = 0

    /**
     * Returned by [getLocalAddress] / [getRemoteAddress] /
     * [acceptClient] when the per-fd queue is empty. `.first` is
     * treated as remote, `.second` as local; `acceptClient` returns
     * the pair as-is.
     */
    public var defaultAddresses: Pair<SocketAddress, SocketAddress> =
        InetSocketAddress(Host.Ip(IpAddress.V4.ANY), 0) to InetSocketAddress(Host.Ip(IpAddress.V4.ANY), 0)

    // --- Call tracking ---

    public var createServerSocketCalls: Int = 0
        private set
    public var createReusePortServerSocketCalls: Int = 0
        private set
    public var createUnixServerSocketCalls: Int = 0
        private set
    public var createUnconnectedSocketCalls: Int = 0
        private set
    public var createUnixUnconnectedSocketCalls: Int = 0
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
    public var acceptClientCalls: Int = 0
        private set

    private val _createdFds = mutableListOf<Int>()
    private val _nonBlockingFds = mutableListOf<Int>()

    /** Ordered list of fds returned by any `create*` method. */
    public val createdFds: List<Int> get() = _createdFds.toList()

    /** Ordered list of fds passed to [setNonBlocking] (direct calls only, not chain-nested). */
    public val nonBlockingFds: List<Int> get() = _nonBlockingFds.toList()

    private fun allocateFd(queue: ArrayDeque<Int>): Int {
        val fd = queue.removeFirstOrNull() ?: nextCreatedFd++
        _createdFds.add(fd)
        return fd
    }

    // --- NativeSocketOps impl ---

    override fun createServerSocket(address: IpAddress, port: Int, backlog: Int, logger: Logger): Int {
        createServerSocketCalls++
        return allocateFd(createServerSocketQueue)
    }

    override fun createReusePortServerSocket(address: IpAddress, port: Int, backlog: Int, logger: Logger): Int {
        createReusePortServerSocketCalls++
        return allocateFd(createReusePortServerSocketQueue)
    }

    override fun createUnconnectedSocket(family: IpAddress): Int {
        createUnconnectedSocketCalls++
        return allocateFd(createUnconnectedSocketQueue)
    }

    override fun connectNonBlocking(fd: Int, address: IpAddress, port: Int): ConnectResult {
        connectCalls++
        return connectQueue[fd]?.removeFirstOrNull() ?: defaultConnect
    }

    override fun getSocketError(fd: Int): Int {
        getSocketErrorCalls++
        return socketErrorQueue[fd]?.removeFirstOrNull() ?: defaultSocketError
    }

    override fun getLocalAddress(fd: Int): SocketAddress {
        getLocalAddressCalls++
        return localAddressQueue[fd]?.removeFirstOrNull() ?: defaultAddresses.second
    }

    override fun getRemoteAddress(fd: Int): SocketAddress {
        getRemoteAddressCalls++
        return remoteAddressQueue[fd]?.removeFirstOrNull() ?: defaultAddresses.first
    }

    override fun setNonBlocking(fd: Int) {
        setNonBlockingCalls++
        _nonBlockingFds.add(fd)
    }

    override fun acceptClient(clientFd: Int): Pair<SocketAddress, SocketAddress> {
        acceptClientCalls++
        return acceptClientQueue[clientFd]?.removeFirstOrNull() ?: defaultAddresses
    }

    override fun createUnixServerSocket(address: UnixSocketAddress, backlog: Int, logger: Logger): Int {
        createUnixServerSocketCalls++
        return allocateFd(createUnixServerSocketQueue)
    }

    override fun createUnixUnconnectedSocket(): Int {
        createUnixUnconnectedSocketCalls++
        return allocateFd(createUnixUnconnectedSocketQueue)
    }

    override fun connectUnixNonBlocking(fd: Int, address: UnixSocketAddress): ConnectResult {
        connectUnixCalls++
        return connectUnixQueue[fd]?.removeFirstOrNull() ?: defaultConnect
    }

    // --- Script setup ---

    /** Appends fds the fake will hand out from `createServerSocket`. */
    public fun enqueueCreateServerSocket(vararg fds: Int) {
        createServerSocketQueue.addAll(fds.toList())
    }

    /** Appends fds the fake will hand out from `createReusePortServerSocket`. */
    public fun enqueueCreateReusePortServerSocket(vararg fds: Int) {
        createReusePortServerSocketQueue.addAll(fds.toList())
    }

    /** Appends fds the fake will hand out from `createUnixServerSocket`. */
    public fun enqueueCreateUnixServerSocket(vararg fds: Int) {
        createUnixServerSocketQueue.addAll(fds.toList())
    }

    /** Appends fds the fake will hand out from `createUnconnectedSocket`. */
    public fun enqueueCreateUnconnectedSocket(vararg fds: Int) {
        createUnconnectedSocketQueue.addAll(fds.toList())
    }

    /** Appends fds the fake will hand out from `createUnixUnconnectedSocket`. */
    public fun enqueueCreateUnixUnconnectedSocket(vararg fds: Int) {
        createUnixUnconnectedSocketQueue.addAll(fds.toList())
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

    /** Appends scripted `(remote, local)` pairs for `acceptClient(clientFd)`. */
    public fun enqueueAcceptClient(clientFd: Int, vararg pairs: Pair<SocketAddress, SocketAddress>) {
        acceptClientQueue.getOrPut(clientFd) { ArrayDeque() }.addAll(pairs)
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
            if (createServerSocketQueue.isNotEmpty()) add("createServerSocket: ${createServerSocketQueue.size} remaining")
            if (createReusePortServerSocketQueue.isNotEmpty()) {
                add("createReusePortServerSocket: ${createReusePortServerSocketQueue.size} remaining")
            }
            if (createUnixServerSocketQueue.isNotEmpty()) {
                add("createUnixServerSocket: ${createUnixServerSocketQueue.size} remaining")
            }
            if (createUnconnectedSocketQueue.isNotEmpty()) {
                add("createUnconnectedSocket: ${createUnconnectedSocketQueue.size} remaining")
            }
            if (createUnixUnconnectedSocketQueue.isNotEmpty()) {
                add("createUnixUnconnectedSocket: ${createUnixUnconnectedSocketQueue.size} remaining")
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
            report("acceptClient", acceptClientQueue)
        }
        check(leftovers.isEmpty()) { "unconsumed scripted responses: $leftovers" }
    }
}
