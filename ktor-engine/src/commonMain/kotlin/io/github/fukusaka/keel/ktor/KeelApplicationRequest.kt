package io.github.fukusaka.keel.ktor

import io.github.fukusaka.keel.codec.http.HttpRequestHead
import io.github.fukusaka.keel.core.SocketAddress
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.RequestConnectionPoint
import io.ktor.http.parseQueryString
import io.ktor.server.engine.BaseApplicationRequest
import io.ktor.server.request.RequestCookies
import io.ktor.utils.io.ByteReadChannel

/**
 * Ktor [BaseApplicationRequest] that reads request metadata from a keel [HttpRequestHead].
 *
 * Query parameters are lazily parsed from the raw URI.
 * Headers are adapted via [KeelHeaders].
 */
internal class KeelApplicationRequest(
    call: KeelApplicationCall,
    private val head: HttpRequestHead,
    localAddress: SocketAddress?,
    remoteAddress: SocketAddress?,
    override val engineReceiveChannel: ByteReadChannel,
) : BaseApplicationRequest(call) {

    override val cookies: RequestCookies by lazy { RequestCookies(this) }

    override var engineHeaders: Headers = KeelHeaders(head.headers)

    override val queryParameters: Parameters by lazy {
        val uri = head.uri
        val queryStart = uri.indexOf('?').takeIf { it != -1 } ?: return@lazy Parameters.Empty
        parseQueryString(uri, startIndex = queryStart + 1)
    }

    override val rawQueryParameters: Parameters by lazy {
        val uri = head.uri
        val queryStart = uri.indexOf('?').takeIf { it != -1 } ?: return@lazy Parameters.Empty
        parseQueryString(uri, startIndex = queryStart + 1, decode = false)
    }

    override val local: RequestConnectionPoint = KeelConnectionPoint(
        localAddr = localAddress,
        remoteAddr = remoteAddress,
        version = head.version.text,
        uri = head.uri,
        hostHeaderValue = head.headers["Host"],
        method = HttpMethod.parse(head.method.name),
    )
}
