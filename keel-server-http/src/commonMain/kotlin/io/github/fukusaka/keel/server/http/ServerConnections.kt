package io.github.fukusaka.keel.server.http

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Registry of live server connections, consulted by [KeelHttpServer.stop]
 * to drive a graceful shutdown.
 *
 * **Sharded by EventLoop thread.** Every keel channel runs all of its
 * callbacks on a single owning EventLoop thread (the engine's I/O
 * ownership invariant). The registry exploits this: one [Shard] per
 * EventLoop thread, each holding a plain [MutableSet] that only that
 * thread ever mutates. A connection's [HttpServerHandler] joins its
 * thread's shard on `onActive` and leaves on `onInactive` with a direct,
 * lock-free `add` / `remove` — no coroutine launch and no mutex on the
 * per-connection path.
 *
 * The shard list itself is appended once per EventLoop thread (at most
 * the engine's thread count, over the whole server lifetime) via a
 * lock-free copy-on-write CAS, and is effectively immutable thereafter.
 * [snapshot] — used only by [KeelHttpServer.stop] — hops onto each
 * shard's dispatcher to read it race-free.
 *
 * A single instance backs one run of a [KeelHttpServer].
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ServerConnections {

    /**
     * Copy-on-write shard list. Appended once per EventLoop thread, then
     * stable; read lock-free by every connection's `onActive`.
     */
    private val shards = AtomicReference<List<Shard>>(emptyList())

    /**
     * Returns the [Shard] for [dispatcher], creating it on first use.
     * Non-suspend and lock-free — a CAS-retry append. Must be called on
     * the EventLoop thread that owns [dispatcher]; the returned shard's
     * handler set is then mutated only by that thread.
     */
    fun shardFor(dispatcher: CoroutineDispatcher): Shard {
        while (true) {
            val current = shards.load()
            current.firstOrNull { it.dispatcher === dispatcher }?.let { return it }
            val created = Shard(dispatcher)
            if (shards.compareAndSet(current, current + created)) return created
            // Lost the race to another EventLoop thread's first connection
            // — retry; the next load observes the appended list.
        }
    }

    /**
     * A point-in-time copy of every registered connection. Each shard's
     * set is read on the shard's own EventLoop thread, so the snapshot
     * never races a concurrent `onActive` / `onInactive`.
     */
    suspend fun snapshot(): List<HttpServerHandler> =
        shards.load().flatMap { shard ->
            withContext(shard.dispatcher) { shard.handlers.toList() }
        }
}

/**
 * A per-EventLoop-thread shard of the [ServerConnections] registry.
 * [handlers] is mutated only by the thread that owns [dispatcher], so it
 * needs no synchronization (see [ServerConnections]).
 */
internal class Shard(val dispatcher: CoroutineDispatcher) {
    val handlers: MutableSet<HttpServerHandler> = mutableSetOf()
}
