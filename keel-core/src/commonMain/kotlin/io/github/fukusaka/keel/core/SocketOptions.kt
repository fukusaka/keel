package io.github.fukusaka.keel.core

/**
 * User-settable socket options applied during bind / connect / accept.
 *
 * Typed properties for the common POSIX options most applications
 * need to tune. `null` values leave the kernel default unchanged —
 * engines only issue a `setsockopt(2)` call for non-null properties.
 *
 * ## Scope
 *
 * - **`ConnectConfig.socketOptions`**: applied to the client socket
 *   before `connect(2)`. Covers the connection initiator.
 * - **`BindConfig.childSocketOptions`**: applied to every accepted
 *   client socket immediately after `accept(2)`. Listener-side
 *   options (`SO_REUSEADDR` / `SO_REUSEPORT`) are kernel invariants
 *   and not exposed here — they are set unconditionally by
 *   [io.github.fukusaka.keel.native.posix.NativeSocketOps.bindListener].
 *
 * ## Semantics
 *
 * - [tcpNoDelay]: `TCP_NODELAY`. Disables Nagle's algorithm.
 *   Recommended `true` for latency-sensitive protocols (HTTP, RPC).
 * - [keepAlive]: `SO_KEEPALIVE`. Enables TCP keepalive probes so
 *   dead connections are detected without application-level heart
 *   beats. Default probe interval is OS-specific (typically hours).
 * - [receiveBufferSize]: `SO_RCVBUF`. Suggests kernel receive buffer
 *   size. The kernel may double or cap the value per
 *   `/proc/sys/net/core/rmem_{default,max}` (Linux) or
 *   `net.inet.tcp.recvspace` (Darwin).
 * - [sendBufferSize]: `SO_SNDBUF`. Same contract as
 *   [receiveBufferSize] for the send direction.
 *
 * ## Engine coverage
 *
 * - **POSIX engines** (`engine-epoll` / `engine-kqueue` / `engine-io-uring`):
 *   all four options supported via `setsockopt(2)`.
 * - **JVM engines** (`engine-nio` / `engine-netty`): all four supported
 *   via `SocketChannel.setOption` / Netty `ChannelOption`.
 * - **NWConnection** (macOS `engine-nwconnection`) and **Node.js**
 *   (`engine-nodejs`): only [tcpNoDelay] and [keepAlive] supported —
 *   NW framework / Node.js `net.Socket` do not expose buffer-size APIs
 *   ([receiveBufferSize] / [sendBufferSize] are silently ignored).
 *
 * ## Escape hatch
 *
 * Rare options (`IP_TOS`, `TCP_CORK`, `TCP_FASTOPEN`, etc.) are not
 * typed here. Users who need them can post-process the accepted /
 * connected fd via a pipeline handler that reaches for
 * `platform.posix.setsockopt` directly — this is rare enough that
 * a typed API is not yet justified.
 */
public data class SocketOptions(
    public val tcpNoDelay: Boolean? = null,
    public val keepAlive: Boolean? = null,
    public val receiveBufferSize: Int? = null,
    public val sendBufferSize: Int? = null,
) {
    /** True when at least one option is set (no-op shortcut). */
    public val isEmpty: Boolean
        get() = tcpNoDelay == null &&
            keepAlive == null &&
            receiveBufferSize == null &&
            sendBufferSize == null

    public companion object {
        /** No options set — engines skip the setsockopt pass. */
        public val DEFAULT: SocketOptions = SocketOptions()
    }
}

/**
 * Discriminated socket option for [NativeSocketOps.setSocketOption].
 *
 * Sealed to give the production impl a single `when` dispatch point
 * and the test fake a clean scriptable type. [SocketOptions] is the
 * user-facing API that fans out into these discrete variants inside
 * the engine's "apply options" helper.
 */
public sealed class SocketOption {
    public data class TcpNoDelay(val enabled: Boolean) : SocketOption()
    public data class KeepAlive(val enabled: Boolean) : SocketOption()
    public data class ReceiveBufferSize(val bytes: Int) : SocketOption()
    public data class SendBufferSize(val bytes: Int) : SocketOption()
}
