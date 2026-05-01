package io.github.fukusaka.keel.server.ktor.cio

import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.server.ktor.KeelConnectionPoint
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.RequestConnectionPoint
import io.ktor.http.cio.CIOHeaders
import io.ktor.http.cio.Request
import io.ktor.http.parseQueryString
import io.ktor.server.engine.BaseApplicationRequest
import io.ktor.server.request.RequestCookies
import io.ktor.utils.io.ByteReadChannel

/**
 * Ktor [BaseApplicationRequest] wrapping a [Request][io.ktor.http.cio.Request]
 * parsed by `ktor-http-cio`'s [parseRequest][io.ktor.http.cio.parseRequest].
 *
 * The [Request] carries the method / uri / version / headers as [CharSequence]
 * values pointing into the parser's internal `CharArrayBuilder`; we materialise
 * them into [String] only when accessed.  Headers are exposed via
 * [CIOHeaders] which adapts [HttpHeadersMap][io.ktor.http.cio.HttpHeadersMap]
 * to Ktor's [Headers] interface.
 */
internal class KeelCioApplicationRequest(
    call: KeelCioApplicationCall,
    private val cioRequest: Request,
    localAddress: SocketAddress?,
    remoteAddress: SocketAddress?,
    override val engineReceiveChannel: ByteReadChannel,
    scheme: String = "http",
) : BaseApplicationRequest(call) {

    override val cookies: RequestCookies by lazy { RequestCookies(this) }

    override var engineHeaders: Headers = CIOHeaders(cioRequest.headers)

    private val uriString: String by lazy { cioRequest.uri.toString() }

    override val queryParameters: Parameters by lazy {
        val queryStart = uriString.indexOf('?').takeIf { it != -1 } ?: return@lazy Parameters.Empty
        parseQueryString(uriString, startIndex = queryStart + 1)
    }

    override val rawQueryParameters: Parameters by lazy {
        val queryStart = uriString.indexOf('?').takeIf { it != -1 } ?: return@lazy Parameters.Empty
        parseQueryString(uriString, startIndex = queryStart + 1, decode = false)
    }

    override val local: RequestConnectionPoint = KeelConnectionPoint(
        localAddr = localAddress,
        remoteAddr = remoteAddress,
        version = cioRequest.version.toString(),
        uri = uriString,
        hostHeaderValue = cioRequest.headers["Host"]?.toString(),
        method = HttpMethod.parse(cioRequest.method.value),
        scheme = scheme,
    )
}
