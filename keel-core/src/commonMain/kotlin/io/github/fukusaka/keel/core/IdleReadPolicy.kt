package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.pipeline.PipelinedChannel

/**
 * Engine-side policy for what happens **while the channel is idle on the
 * read side** — i.e. while [PipelinedChannel.readEnabled] is `false`. The
 * two values capture a trade-off that exists only in this idle window;
 * when `readEnabled = true` both policies behave identically (the engine
 * is actively delivering bytes through `onRead` either way).
 *
 * **The trade-off arises from a structural property of the underlying
 * I/O API**: some platforms can observe peer FIN without consuming data
 * (kqueue's `EV_EOF` flag, epoll's `EPOLLRDHUP`, Node.js's separate
 * `'end'` event), and others cannot (Java NIO `Selector` exposes only
 * `POLLIN` not `POLLRDHUP`; Apple's NWConnection has no event-readiness
 * API distinct from `nw_connection_receive` data delivery). On the
 * latter, peer-close detection requires an active read syscall, which in
 * turn drains kernel- or framework-level TCP back-pressure. The user
 * picks which side of the trade-off matches their workload.
 *
 * **Engine applicability**: only consulted by engines that face the
 * structural constraint above:
 *
 * | engine | reads this policy | reason |
 * |---|---|---|
 * | engine-nio | yes | Java NIO `Selector` API limit (no `POLLRDHUP`) |
 * | engine-netty (`NettyTransport.Nio` only) | yes | same Selector limit |
 * | engine-nwconnection | yes | NWConnection has no ready-without-consume API |
 * | engine-kqueue | no | `EV_EOF` flag observable while `EVFILT_READ` is armed without reading |
 * | engine-epoll | no | `EPOLLRDHUP` observable likewise |
 * | engine-io-uring | no | same kernel-level event flag as epoll |
 * | engine-netty (`NettyTransport.Epoll` / `KQueue`) | no | native transport reuses kqueue / epoll mechanism |
 * | engine-nodejs | no | Node `net.Socket` exposes peer FIN through a separate `'end'` event independent of the data listener |
 *
 * Engines marked "no" achieve both peer-close detection and TCP
 * back-pressure simultaneously and silently ignore [IoEngineConfig.idleReadPolicy].
 *
 * **Default**: [PRESERVE_BACKPRESSURE]. Matches the existing keel
 * behaviour for the affected engines (lazy-arm of the underlying read
 * primitive), so adopting [IdleReadPolicy] in [IoEngineConfig] does not
 * change the contract of any existing connection. Workloads that need
 * peer-close detection while `readEnabled = false` (write-only push
 * clients, monitoring forwarders) opt in to [DETECT_PEER_CLOSE]
 * explicitly and accept its caveat — currently, bytes the peer sends
 * before the channel's pipeline acquires its first user inbound
 * handler are released by `TailHandler` (a `WARN` is logged) because
 * `DefaultPipeline` does not yet buffer pre-attach inbound events.
 *
 * The default may flip to [DETECT_PEER_CLOSE] in a future BREAKING
 * release once `DefaultPipeline` gains a pre-attach event journal
 * (planned follow-up) and a per-handler lifecycle replay mechanism
 * (generalisation of the existing `inactiveObserved` flag from PR #467
 * so all `InboundHandler` instances added to an already-active pipeline
 * receive the current `active` / `inactive` / `writability` state).
 * Together, those follow-ups remove the data-loss caveat that justifies
 * the conservative default chosen for this PR.
 */
public enum class IdleReadPolicy {
    /**
     * Surface peer FIN through `IoTransport.onReadClosed` even when
     * [PipelinedChannel.readEnabled] is `false`. The engine arms (or
     * keeps active) the underlying read primitive at all times, so peer
     * close is observed within milliseconds. The cost is that bytes the
     * peer sends while `readEnabled = false` are consumed by the engine
     * (drained from kernel `rcvbuf` on engine-nio / engine-netty NIO
     * fallback, drained from the NWConnection framework receive buffer
     * on engine-nwconnection); kernel-level TCP back-pressure is not
     * preserved. Codec-level / handler-level back-pressure built on
     * suspend channels remains effective.
     *
     * Recommended when:
     * - the channel is write-only or write-mostly (push client, log
     *   forwarder, metrics exporter) and peer FIN must not linger in
     *   `CLOSE-WAIT` until `SO_KEEPALIVE` expires;
     * - higher-level codec handles flow control (e.g. WebSocket
     *   ping/pong, HTTP/2 flow-control windows, application-level
     *   ACKs).
     */
    DETECT_PEER_CLOSE,

    /**
     * Keep the underlying read primitive disarmed while
     * [PipelinedChannel.readEnabled] is `false`, so kernel `rcvbuf`
     * (engine-nio / engine-netty NIO fallback) or the NWConnection
     * receive buffer (engine-nwconnection) retains the bytes and the
     * peer's TCP window stalls. The cost is that peer FIN is not
     * surfaced through `onReadClosed` until either the next time
     * `readEnabled` flips to `true` or `SO_KEEPALIVE` declares the
     * peer dead (default ~2 hours).
     *
     * Recommended when:
     * - kernel-level TCP back-pressure is required by the workload
     *   (bulk transfer, large upload streaming, no application-level
     *   flow control);
     * - the channel is reliably bidirectional and `readEnabled = true`
     *   is held for the majority of the connection lifetime, so the
     *   peer-close detection window is short.
     */
    PRESERVE_BACKPRESSURE,
}
