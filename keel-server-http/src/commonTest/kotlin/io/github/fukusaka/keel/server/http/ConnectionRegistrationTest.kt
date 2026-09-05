package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * That a live connection is in the registry its server shuts down through.
 *
 * `HttpServerHandler.onActive` is the only thing that puts a connection into
 * that registry, and `onActive` only runs if something tells the pipeline the
 * channel is active. Nothing did: `notifyActive` had no caller anywhere in the
 * library. So the registry was empty on every server, and every part of
 * `stop()` that reads it — the drain request, both waits for connections to
 * close, and the force-close — reached nothing. (`stop()` still cancelled the
 * scope those connections' request handlers run in, so it was not inert; it
 * simply never closed a connection.)
 *
 * There is an existing case that asserts a connection joins the shard and
 * leaves it, and it passes on either tree — because it fires both signals
 * itself. What nothing asked is whether anything *sends* them. That is the
 * question here, and it is why these install the pipeline and then act on the
 * channel, without touching the signals.
 *
 * The two are one property, not two. Joining without leaving is worse than
 * neither: a registration that no ending removes accumulates a handler, its
 * job and the whole pipeline graph for every connection the server drops — and
 * dropping them is what the deadline handlers do all day.
 */
internal class ConnectionRegistrationTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}

    @AfterTest
    fun tearDown() {
        scope.cancel()
        transport.close()
    }

    @Test
    fun `a connection joins the registry its server shuts down through`() = runTest(timeout = TIMEOUT) {
        val connections = ServerConnections()

        channel.installHttpServerPipeline(
            router = Router(),
            middlewares = emptyList(),
            errorHandlers = ErrorHandlers.DEFAULT,
            queryParameterConfig = QueryParameterConfig.DEFAULT,
            scope = scope,
            connections = connections,
        )

        assertEquals(
            1,
            connections.snapshot().size,
            "the connection is in the registry — this is what a graceful shutdown drains and then closes",
        )
    }

    @Test
    fun `a connection the server closes itself leaves the registry`() = runTest(timeout = TIMEOUT) {
        val connections = ServerConnections()
        channel.installHttpServerPipeline(
            router = Router(),
            middlewares = emptyList(),
            errorHandlers = ErrorHandlers.DEFAULT,
            queryParameterConfig = QueryParameterConfig.DEFAULT,
            scope = scope,
            connections = connections,
        )
        assertEquals(1, connections.snapshot().size, "it is in the registry to begin with")

        // What the server does to a client that never finishes its headers:
        // a deadline handler calls close() on the channel. No peer FIN
        // arrives, and no transport but one reports a local close as a read
        // close — so this is the path on which an entry that only joins would
        // never leave.
        channel.close()

        assertEquals(
            0,
            connections.snapshot().size,
            "and it is out again — an entry that joins on activation and never leaves on a close the " +
                "server itself made would accumulate one handler, job and pipeline per dropped connection",
        )
    }

    @Test
    fun `a handler above the server stack that throws on the lifecycle does not keep the connection out of or in the registry`() =
        runTest(timeout = TIMEOUT) {
            // The readiness engines defer the journal drain, so the whole
            // stack is assembled before the activation sweeps it. A handler
            // above the server's that throws on that sweep used to end it
            // there: the connection never joined the registry, invisible to
            // the server's own stop for its whole life. And one throwing on
            // the ending used to leave the entry behind after the connection
            // was gone. Measured on the real stack before the sweep was made
            // to continue past a throw.
            // Built here rather than from the fixture: the pipeline captures
            // the transport's dispatcher when the channel is constructed, so
            // the queue has to be in place before that or the drain runs
            // inline and the sweep never crosses the thrower at all.
            val queue = QueueingDispatcher()
            val deferring = TestIoTransport().apply { dispatcher = queue }
            val channel = object : AbstractPipelinedChannel(deferring, PrintLogger("test")) {}
            val connections = ServerConnections()
            channel.pipeline.addLast(
                "thrower",
                object : InboundHandler {
                    override fun onActive(ctx: PipelineHandlerContext) = throw IllegalStateException("active")
                    override fun onInactive(ctx: PipelineHandlerContext) = throw IllegalStateException("inactive")
                },
            )
            channel.installHttpServerPipeline(
                router = Router(),
                middlewares = emptyList(),
                errorHandlers = ErrorHandlers.DEFAULT,
                queryParameterConfig = QueryParameterConfig.DEFAULT,
                scope = scope,
                connections = connections,
            )
            queue.runQueued()
            assertEquals(1, connections.snapshot().size, "the activation reached the server's handler past the throw")

            channel.close()

            assertEquals(0, connections.snapshot().size, "and so did the ending")
            deferring.close()
        }

    /**
     * Holds dispatched work until asked, so the journal drain is deferred as
     * it is on a readiness engine; runs everything inline once [runQueued] has
     * run, since the registry's snapshot hops onto this same dispatcher.
     */
    private class QueueingDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()
        private var inline = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (inline) block.run() else queued.addLast(block)
        }

        fun runQueued() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
            inline = true
        }
    }

    private companion object {
        val TIMEOUT = 15.seconds
    }
}
