package io.github.fukusaka.keel.server.ktor.cio

import io.github.fukusaka.keel.core.SocketAddress
import io.ktor.http.cio.Request
import io.ktor.server.application.Application
import io.ktor.server.engine.BaseApplicationCall
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext

/**
 * Ktor [BaseApplicationCall] backed by a single keel connection paired with
 * `ktor-http-cio`'s [Request] parser output.
 *
 * The [scheme] parameter ("http" or "https") is propagated to
 * `KeelConnectionPoint` so Ktor's `RequestConnectionPoint` reports the correct
 * protocol and default port.
 */
@Suppress("LongParameterList")
internal class KeelCioApplicationCall(
    application: Application,
    cioRequest: Request,
    requestBody: ByteReadChannel,
    output: ByteWriteChannel,
    localAddress: SocketAddress?,
    remoteAddress: SocketAddress?,
    scope: CoroutineScope,
    override val coroutineContext: CoroutineContext,
    keepAlive: Boolean,
    scheme: String = "http",
) : BaseApplicationCall(application), CoroutineScope {

    override val request = KeelCioApplicationRequest(
        call = this,
        cioRequest = cioRequest,
        localAddress = localAddress,
        remoteAddress = remoteAddress,
        engineReceiveChannel = requestBody,
        scheme = scheme,
    )

    override val response = KeelCioApplicationResponse(
        call = this,
        output = output,
        scope = scope,
        keepAlive = keepAlive,
        protocolVersion = cioRequest.version.toString(),
    )

    init {
        putResponseAttribute()
    }
}
