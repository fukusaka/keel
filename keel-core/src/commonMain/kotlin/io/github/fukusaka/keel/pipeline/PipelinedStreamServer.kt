package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.core.StreamServer

/**
 * A server that accepts connections via Pipeline initializer callbacks.
 *
 * Created by [StreamEngine.bindPipeline]. Unlike [StreamServer] which provides
 * [StreamServer.accept] for app-driven connection handling, a [PipelinedStreamServer]
 * delegates connection acceptance to the engine — each accepted connection
 * is configured via the `pipelineInitializer` callback passed to
 * [StreamEngine.bindPipeline].
 *
 * ```
 * val server = engine.bindPipeline("0.0.0.0", 8080, config = tlsBindConfig) { channel ->
 *     channel.pipeline.addLast("http", HttpHandler())
 * }
 * println("Listening on ${server.localAddress}")
 * // ... server runs until close
 * server.close()
 * ```
 */
interface PipelinedStreamServer : AutoCloseable {

    /**
     * Local address this server is bound to. A multi-address server
     * (created by the list-taking [StreamEngine.bindPipeline] overload)
     * reports its first address in bind order; [localAddresses] carries
     * them all.
     */
    val localAddress: SocketAddress

    /**
     * The addresses this server is currently bound to, in bind order.
     * Single-address servers (the default) report `[localAddress]`.
     */
    val localAddresses: List<SocketAddress> get() = listOf(localAddress)

    /** True if the server is listening for connections. */
    val isActive: Boolean

    /**
     * The subset of [localAddresses] still able to accept connections.
     *
     * An engine with per-listener teardown removes an address whose listener
     * it had to close — the readiness engines do this when the kernel refuses
     * the listener's accept arm, releasing the port so a connect is refused
     * promptly instead of parking in a backlog nobody drains — while its
     * siblings keep accepting and [isActive] stays true until the last one
     * goes. Engines without per-listener teardown report every address while
     * [isActive] and none after — there `activeLocalAddresses.size <
     * localAddresses.size` never fires, so the comparison detects partial
     * degradation only on engines that track it. After [close] the list is
     * eventually empty: the release is asynchronous, like the port's.
     */
    val activeLocalAddresses: List<SocketAddress>
        get() = if (isActive) localAddresses else emptyList()

    /**
     * Stops listening and releases resources.
     *
     * **The listening port is released asynchronously.** `close()` returns once
     * the teardown has been handed to the engine, not once the kernel has
     * dropped the socket, so a `bind()` for the same port immediately afterwards
     * may still fail with "address already in use". The release is prompt —
     * bounded by one event-loop turn plus the kernel's own work — so a caller
     * that must rebind should retry briefly rather than assume either outcome
     * on the first attempt.
     *
     * This is the contract every engine implements: the ones backed by
     * io_uring, `Selector` or a framework loop cannot make it synchronous
     * without blocking their loop, and the readiness engines follow the same
     * rule so callers do not have to know which engine they hold.
     *
     * Idempotent. Connections already accepted are unaffected and continue
     * until they close on their own.
     */
    override fun close()
}
