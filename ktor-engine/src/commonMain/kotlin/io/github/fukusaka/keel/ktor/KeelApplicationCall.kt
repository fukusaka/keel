package io.github.fukusaka.keel.ktor

import io.github.fukusaka.keel.codec.http.HttpRequestHead
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.io.BufferedSuspendSink
import io.ktor.server.application.Application
import io.ktor.server.engine.BaseApplicationCall
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext

/**
 * Ktor [BaseApplicationCall] backed by a single keel [Channel][io.github.fukusaka.keel.core.Channel] connection.
 *
 * Bridges the parsed [HttpRequestHead] and raw I/O streams ([ByteReadChannel] for request body,
 * [BufferedSuspendSink] for response output) into Ktor's request/response hierarchy.
 *
 * The [scheme] parameter ("http" or "https") is propagated to [KeelConnectionPoint]
 * so that Ktor's [RequestConnectionPoint][io.ktor.http.RequestConnectionPoint] reports
 * the correct protocol and default port.
 */
@Suppress("LongParameterList") // scheme added for HTTPS; refactoring to a context object is Phase 10.
internal class KeelApplicationCall(
    application: Application,
    head: HttpRequestHead,
    localAddress: SocketAddress?,
    remoteAddress: SocketAddress?,
    requestBody: ByteReadChannel,
    sink: BufferedSuspendSink,
    scope: CoroutineScope,
    override val coroutineContext: CoroutineContext,
    keepAlive: Boolean,
    scheme: String = "http",
) : BaseApplicationCall(application), CoroutineScope {

    override val request = KeelApplicationRequest(
        call = this,
        head = head,
        localAddress = localAddress,
        remoteAddress = remoteAddress,
        engineReceiveChannel = requestBody,
        scheme = scheme,
    )

    override val response = KeelApplicationResponse(
        call = this,
        sink = sink,
        scope = scope,
        keepAlive = keepAlive,
    )

    init {
        putResponseAttribute()
    }
}
