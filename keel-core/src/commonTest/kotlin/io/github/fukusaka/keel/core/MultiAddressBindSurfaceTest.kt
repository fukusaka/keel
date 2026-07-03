package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.pipeline.PipelinedStreamServer
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Default-member contract of the multi-address bind surface:
 * [PipelinedStreamServer.localAddresses] falls back to the single
 * [PipelinedStreamServer.localAddress], and the list-taking
 * [StreamEngine.bindPipeline] default validates its argument before
 * reporting the engine as unsupported. Purely synchronous — no timeout
 * needed.
 */
class MultiAddressBindSurfaceTest {

    /** Minimal single-address server relying on every interface default. */
    private class SingleAddressServer(
        override val localAddress: SocketAddress,
    ) : PipelinedStreamServer {
        override val isActive: Boolean get() = true
        override fun close() {}
    }

    /** Engine implementing only the abstract members; bindPipeline stays default. */
    private class DefaultsOnlyEngine : StreamEngine {
        override val coroutineContext: CoroutineContext = EmptyCoroutineContext
        override val config: IoEngineConfig = IoEngineConfig()
        override suspend fun close() {}
        override suspend fun bind(address: SocketAddress, bindConfig: BindConfig): StreamServer =
            error("not used by this test")
        override suspend fun connect(address: SocketAddress): Channel =
            error("not used by this test")
    }

    @Test
    fun `localAddresses defaults to the single localAddress`() {
        val address = InetSocketAddress("127.0.0.1", 8080)
        val server = SingleAddressServer(address)
        assertEquals(listOf<SocketAddress>(address), server.localAddresses)
    }

    @Test
    fun `multi-address bindPipeline default rejects an empty bind list`() {
        assertFailsWith<IllegalArgumentException> {
            DefaultsOnlyEngine().bindPipeline(emptyList()) { }
        }
    }

    @Test
    fun `multi-address bindPipeline default reports unsupported for a non-empty bind list`() {
        val failure = assertFailsWith<UnsupportedOperationException> {
            DefaultsOnlyEngine().bindPipeline(
                listOf(BindSpec(InetSocketAddress("127.0.0.1", 8080))),
            ) { }
        }
        assertContains(failure.message.orEmpty(), "multi-address")
    }
}
