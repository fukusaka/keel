package io.github.fukusaka.keel.ktor

import io.github.fukusaka.keel.codec.http.HttpRequestHead
import io.github.fukusaka.keel.core.SocketAddress
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.Sink
import kotlin.coroutines.CoroutineContext

/**
 * Ktor [BaseApplicationCall] backed by a single keel [Channel][io.github.fukusaka.keel.core.Channel] connection.
 *
 * Bridges the parsed [HttpRequestHead] and raw I/O streams ([ByteReadChannel] for request body,
 * [Sink] for response output) into Ktor's request/response hierarchy.
 */
internal class KeelApplicationCall(
    application: Application,
    head: HttpRequestHead,
    localAddress: SocketAddress?,
    remoteAddress: SocketAddress?,
    requestBody: ByteReadChannel,
    sink: Sink,
    scope: CoroutineScope,
    override val coroutineContext: CoroutineContext,
    keepAlive: Boolean,
) : BaseApplicationCall(application), CoroutineScope {

    override val request = KeelApplicationRequest(
        call = this,
        head = head,
        localAddress = localAddress,
        remoteAddress = remoteAddress,
        engineReceiveChannel = requestBody,
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
