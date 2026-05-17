package io.github.fukusaka.keel.server.http

import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Unit tests for [ServerConnections]' EventLoop-thread sharding — one
 * [Shard] per dispatcher, created on first use.
 */
internal class ServerConnectionsTest {

    @Test
    fun `shardFor returns the same shard for the same dispatcher`() {
        val connections = ServerConnections()
        val first = connections.shardFor(Dispatchers.Unconfined)
        val again = connections.shardFor(Dispatchers.Unconfined)
        assertSame(first, again, "one EventLoop dispatcher maps to a single shard")
    }

    @Test
    fun `shardFor returns distinct shards for distinct dispatchers`() {
        val connections = ServerConnections()
        val unconfined = connections.shardFor(Dispatchers.Unconfined)
        val default = connections.shardFor(Dispatchers.Default)
        assertNotSame(unconfined, default, "each EventLoop dispatcher gets its own shard")
    }
}
