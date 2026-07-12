package io.github.fukusaka.keel.engine.netty

import io.netty.channel.Channel
import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.ServerChannel
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollDomainSocketChannel
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.epoll.EpollServerDomainSocketChannel
import io.netty.channel.epoll.EpollServerSocketChannel
import io.netty.channel.epoll.EpollSocketChannel
import io.netty.channel.kqueue.KQueue
import io.netty.channel.kqueue.KQueueDomainSocketChannel
import io.netty.channel.kqueue.KQueueEventLoopGroup
import io.netty.channel.kqueue.KQueueServerDomainSocketChannel
import io.netty.channel.kqueue.KQueueServerSocketChannel
import io.netty.channel.kqueue.KQueueSocketChannel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioDomainSocketChannel
import io.netty.channel.socket.nio.NioServerDomainSocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.channel.unix.DomainSocketAddress
import io.netty.channel.uring.IoUringDomainSocketChannel
import io.netty.channel.uring.IoUringIoHandler
import io.netty.channel.uring.IoUringServerDomainSocketChannel
import io.netty.channel.uring.IoUringServerSocketChannel
import io.netty.channel.uring.IoUringSocketChannel
import java.net.SocketAddress
import java.net.UnixDomainSocketAddress

/**
 * Strategy for the underlying Netty transport implementation, selected
 * automatically based on platform availability.
 *
 * The selection criterion is whether the transport can detect peer
 * close (`channelInactive` / `ChannelInputShutdownEvent`) when
 * `Channel.config().setAutoRead(false)` is in effect. With back-pressure
 * applied (the user has set [io.github.fukusaka.keel.pipeline.PipelinedChannel.readEnabled]
 * to `false`), we still want to surface peer FIN to user code via
 * `onReadClosed` so that write-only push clients (one-direction logger,
 * monitoring metrics sender) do not linger in CLOSE-WAIT until the
 * `SO_KEEPALIVE` timer (~2 hours).
 *
 * Empirically (probe tests at the time of writing):
 *
 * | Transport | autoRead=false + peer FIN | Notes |
 * |-----------|---------------------------|-------|
 * | [Epoll]   | `channelInactive` / `ChannelInputShutdownEvent` fires | JNI calls `epoll_ctl(EPOLLIN \| EPOLLRDHUP)` directly |
 * | [KQueue]  | `channelInactive` / `ChannelInputShutdownEvent` fires | JNI calls `kevent(EVFILT_READ)` directly, observes `EV_EOF` |
 * | [IoUring] | `channelInactive` / `ChannelInputShutdownEvent` fires | Schedules a `POLLRDHUP` `IORING_OP_POLL_ADD` on channel-active independent of the read path (`AbstractIoUringChannel.schedulePollRdHup` / `autoReadCleared` only cancels outstanding reads, not the RDHUP poll — confirmed by reading Netty 4.2.15.Final's `io.netty.channel.uring` sources) |
 * | [Nio]     | no callback fires (3 s timeout) | Java NIO `Selector` only requests `POLLIN`; without `OP_READ` registered, the kernel never delivers the event |
 *
 * The Java NIO `Selector` cannot detect peer FIN without an `OP_READ`
 * registration because [sun.nio.ch.SocketChannelImpl.translateInterestOps]
 * maps `OP_READ` to `Net.POLLIN` only — it never sets `POLLRDHUP` /
 * `EPOLLRDHUP`. With `setAutoRead(false)` Netty does not add `OP_READ`
 * to the selection key, so peer FIN is invisible to the JVM until the
 * application explicitly reads (returning `-1`) or the connection is
 * fully closed (`POLLHUP` from kernel auto-delivery, which fires only
 * on bidirectional close).
 *
 * The native transports ([Epoll] on Linux, [KQueue] on macOS / BSD,
 * [IoUring] on Linux) bypass this constraint by requesting `EPOLLRDHUP` /
 * `POLLRDHUP` / observing `EV_EOF` regardless of the keel-side
 * `readEnabled` state, whether through direct JNI `epoll_ctl` / `kevent`
 * calls ([Epoll] / [KQueue]) or a dedicated `IORING_OP_POLL_ADD`
 * submission independent of read SQEs ([IoUring]).
 *
 * @see Auto for the selection logic.
 */
sealed interface NettyTransport {

    /** Creates an [EventLoopGroup] using this transport's event loop. */
    fun newEventLoopGroup(threads: Int): EventLoopGroup

    /** Class to pass to `ServerBootstrap.channel()` for TCP listen. */
    fun serverSocketChannelClass(): Class<out ServerChannel>

    /** Class to pass to `Bootstrap.channel()` for TCP connect. */
    fun socketChannelClass(): Class<out SocketChannel>

    /** Class to pass to `ServerBootstrap.channel()` for Unix-domain listen. */
    fun serverDomainSocketChannelClass(): Class<out ServerChannel>

    /** Class to pass to `Bootstrap.channel()` for Unix-domain connect. */
    fun domainSocketChannelClass(): Class<out Channel>

    /**
     * Creates a [SocketAddress] for the given Unix-domain socket [path] in
     * the type expected by this transport's bind / connect APIs.
     *
     * Each Netty transport accepts a different `SocketAddress` subtype for
     * Unix-domain socket operations:
     * - [Nio]: [java.net.UnixDomainSocketAddress] (JDK type, filesystem-only)
     * - [Epoll] / [KQueue] / [IoUring]: [io.netty.channel.unix.DomainSocketAddress]
     *   (Netty's path-only address, abstract namespace not supported)
     *
     * Both representations carry only the filesystem path, so translation
     * between them is lossless; the type difference is purely an API
     * surface concern. keel's `UnixSocketAddress` already enforces
     * filesystem-only paths via `requireFilesystemOnly`, so callers can
     * pass `address.path` directly without worrying about abstract-
     * namespace addresses.
     */
    fun newUdsAddress(path: String): SocketAddress

    /** Human-readable name for logging. */
    val name: String

    /**
     * Throws [IllegalStateException] when this transport is not available
     * on the current host (e.g. [Epoll] on macOS, [KQueue] on Linux).
     * [Nio] is always available; [Auto] resolves at access time. Called by
     * [NettyEngine]'s constructor for fail-fast validation when the user
     * explicitly pinned a transport.
     */
    fun requireAvailable() {}

    /** Java NIO transport — fallback when neither Epoll nor KQueue is available. */
    object Nio : NettyTransport {
        override val name: String = "nio"
        override fun newEventLoopGroup(threads: Int): EventLoopGroup = NioEventLoopGroup(threads)
        override fun serverSocketChannelClass() = NioServerSocketChannel::class.java
        override fun socketChannelClass(): Class<out SocketChannel> = NioSocketChannel::class.java
        override fun serverDomainSocketChannelClass() = NioServerDomainSocketChannel::class.java
        override fun domainSocketChannelClass(): Class<out Channel> = NioDomainSocketChannel::class.java
        override fun newUdsAddress(path: String): SocketAddress = UnixDomainSocketAddress.of(path)
    }

    /** Linux native epoll transport. Available iff [Epoll.isAvailable]. */
    object Epoll : NettyTransport {
        override val name: String = "epoll"
        override fun newEventLoopGroup(threads: Int): EventLoopGroup = EpollEventLoopGroup(threads)
        override fun serverSocketChannelClass() = EpollServerSocketChannel::class.java
        override fun socketChannelClass(): Class<out SocketChannel> = EpollSocketChannel::class.java
        override fun serverDomainSocketChannelClass() = EpollServerDomainSocketChannel::class.java
        override fun domainSocketChannelClass(): Class<out Channel> = EpollDomainSocketChannel::class.java
        override fun newUdsAddress(path: String): SocketAddress = DomainSocketAddress(path)
        override fun requireAvailable() {
            check(io.netty.channel.epoll.Epoll.isAvailable()) {
                "NettyTransport.Epoll requires Linux + the netty-transport-native-epoll classifier on the classpath. " +
                    "Cause: ${io.netty.channel.epoll.Epoll.unavailabilityCause()}"
            }
        }
    }

    /** macOS / BSD native kqueue transport. Available iff [KQueue.isAvailable]. */
    object KQueue : NettyTransport {
        override val name: String = "kqueue"
        override fun newEventLoopGroup(threads: Int): EventLoopGroup = KQueueEventLoopGroup(threads)
        override fun serverSocketChannelClass() = KQueueServerSocketChannel::class.java
        override fun socketChannelClass(): Class<out SocketChannel> = KQueueSocketChannel::class.java
        override fun serverDomainSocketChannelClass() = KQueueServerDomainSocketChannel::class.java
        override fun domainSocketChannelClass(): Class<out Channel> = KQueueDomainSocketChannel::class.java
        override fun newUdsAddress(path: String): SocketAddress = DomainSocketAddress(path)
        override fun requireAvailable() {
            check(io.netty.channel.kqueue.KQueue.isAvailable()) {
                "NettyTransport.KQueue requires macOS / BSD + the netty-transport-native-kqueue classifier on the classpath. " +
                    "Cause: ${io.netty.channel.kqueue.KQueue.unavailabilityCause()}"
            }
        }
    }

    /**
     * Linux io_uring transport. Merged into Netty mainline as of 4.2 (package
     * `io.netty.channel.uring`, no longer the separate incubator artifact
     * `io.netty.incubator.channel.uring` / `netty-incubator-transport-native-io_uring`)
     * — bundled by `netty-all`'s own POM (`netty-transport-classes-io_uring`
     * compile dependency, `netty-transport-native-io_uring` runtime
     * dependency per Linux arch classifier), so no extra dependency is
     * needed beyond what [Epoll] already requires.
     *
     * Uses Netty's newer unified [IoHandler][io.netty.channel.IoHandler] /
     * [MultiThreadIoEventLoopGroup] model rather than a dedicated
     * `IoUringEventLoopGroup` subclass (which doesn't exist in this API —
     * unlike [Epoll] / [KQueue], which still construct their own
     * `EventLoopGroup` subtype directly).
     *
     * Not selected by [Auto]: io_uring requires a newer kernel (5.1+
     * minimum, full feature parity ~5.6+) than [Epoll] needs, and Netty's
     * io_uring channel path has different performance characteristics
     * that haven't been benchmarked against [Epoll] for keel's workload
     * shape. Available as an explicit opt-in transport, matching how
     * `keel-engine-io-uring` (the Kotlin/Native cinterop engine) is
     * likewise opt-in rather than a default.
     *
     * Available iff [io.netty.channel.uring.IoUring.isAvailable].
     */
    object IoUring : NettyTransport {
        override val name: String = "io_uring"
        override fun newEventLoopGroup(threads: Int): EventLoopGroup =
            MultiThreadIoEventLoopGroup(threads, IoUringIoHandler.newFactory())
        override fun serverSocketChannelClass() = IoUringServerSocketChannel::class.java
        override fun socketChannelClass(): Class<out SocketChannel> = IoUringSocketChannel::class.java
        override fun serverDomainSocketChannelClass() = IoUringServerDomainSocketChannel::class.java
        override fun domainSocketChannelClass(): Class<out Channel> = IoUringDomainSocketChannel::class.java
        override fun newUdsAddress(path: String): SocketAddress = DomainSocketAddress(path)
        override fun requireAvailable() {
            check(io.netty.channel.uring.IoUring.isAvailable()) {
                "NettyTransport.IoUring requires Linux 5.1+ (kernel io_uring support) + the netty-transport-native-io_uring classifier on the classpath. " +
                    "Cause: ${io.netty.channel.uring.IoUring.unavailabilityCause()}"
            }
        }
    }

    /**
     * Auto-selecting transport: defers to the resolved [delegate] for all
     * channel-class / event-loop-group / availability queries. Resolution
     * happens at first access (lazily), preferring native epoll on Linux,
     * native kqueue on macOS / BSD, then falling back to Java NIO on other
     * JVM platforms (typically Windows). Suitable as the default for most
     * applications that just want the best transport for the host.
     */
    object Auto : NettyTransport {
        override val name: String get() = "auto(${delegate.name})"

        /**
         * Resolves to [Epoll] / [KQueue] / [Nio] based on availability of
         * Netty's native transport binaries (bundled with `netty-all`,
         * loaded automatically when the class is referenced).
         */
        val delegate: NettyTransport by lazy {
            when {
                io.netty.channel.epoll.Epoll.isAvailable() -> Epoll
                io.netty.channel.kqueue.KQueue.isAvailable() -> KQueue
                else -> Nio
            }
        }

        override fun newEventLoopGroup(threads: Int): EventLoopGroup =
            delegate.newEventLoopGroup(threads)

        override fun serverSocketChannelClass(): Class<out ServerChannel> =
            delegate.serverSocketChannelClass()

        override fun socketChannelClass(): Class<out SocketChannel> =
            delegate.socketChannelClass()

        override fun serverDomainSocketChannelClass(): Class<out ServerChannel> =
            delegate.serverDomainSocketChannelClass()

        override fun domainSocketChannelClass(): Class<out Channel> =
            delegate.domainSocketChannelClass()

        override fun newUdsAddress(path: String): SocketAddress =
            delegate.newUdsAddress(path)

        // [Auto.requireAvailable] is a no-op: [Nio] is always available, so
        // the delegate resolves to a usable transport on every JVM platform.
    }
}
