package io.github.fukusaka.keel.engine.netty

import io.netty.channel.Channel
import io.netty.channel.EventLoopGroup
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
 * The native transports ([Epoll] on Linux, [KQueue] on macOS / BSD)
 * bypass this constraint by issuing `epoll_ctl` / `kevent` syscalls
 * directly through JNI, requesting `EPOLLRDHUP` / observing `EV_EOF`
 * regardless of the keel-side `readEnabled` state.
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
     * - [Epoll] / [KQueue]: [io.netty.channel.unix.DomainSocketAddress]
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
