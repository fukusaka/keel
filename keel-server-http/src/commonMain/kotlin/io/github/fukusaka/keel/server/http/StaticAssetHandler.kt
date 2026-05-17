package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseHead
import io.github.fukusaka.keel.codec.http.HttpStatus
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/** Fallback content type when the asset's provider could not resolve one. */
private const val DEFAULT_CONTENT_TYPE = "application/octet-stream"

/** Copy chunk size for streaming an asset body into response buffers. */
private const val STREAM_CHUNK_SIZE = 16_384

/**
 * Serves a single [AssetSource] as a [RouteHandler].
 *
 * The handler percent-decodes the wildcard remainder of the request path
 * exactly once, resolves it against [assetSource], and:
 *
 * - answers `404` when the asset does not resolve;
 * - answers `304 Not Modified` (no body, the asset body is never opened)
 *   when an `If-None-Match` / `If-Modified-Since` precondition holds;
 * - otherwise streams the body with `200 OK`, emitting `Content-Type`,
 *   `Content-Length`, and — when available — `ETag` / `Last-Modified`.
 *
 * `GET` and `HEAD` are both handled; a `HEAD` reply carries the same
 * headers as the corresponding `GET` but no body.
 *
 * Range handling (`206` / `Content-Range`) is intentionally out of
 * scope here — see the G-2b milestone.
 */
internal class StaticAssetHandler(private val assetSource: AssetSource) {

    /** Serves [call] from the configured [AssetSource]. */
    suspend fun handle(call: HttpCall) {
        val wildcard = call.pathParameters["*"] ?: ""
        val decoded = percentDecode(wildcard)
        if (decoded == null) {
            call.respond(HttpResponse.notFound())
            return
        }
        val asset = assetSource.resolve(decoded)
        if (asset == null) {
            call.respond(HttpResponse.notFound())
            return
        }

        if (isNotModified(call, asset)) {
            call.respondStream(HttpResponseHead(HttpStatus.NOT_MODIFIED, headers = conditionalHeaders(asset))) {
                // 304: no body, and the asset body is deliberately never opened.
            }
            return
        }

        val headers = HttpHeaders()
        headers[HttpHeaderName.CONTENT_TYPE] = asset.contentType ?: DEFAULT_CONTENT_TYPE
        headers[HttpHeaderName.CONTENT_LENGTH] = asset.size.toString()
        asset.etag?.let { headers[HttpHeaderName.ETAG] = it }
        asset.lastModified?.let { headers[HttpHeaderName.LAST_MODIFIED] = formatHttpDate(it) }

        val head = HttpResponseHead(HttpStatus.OK, headers = headers)
        if (call.method == HttpMethod.HEAD) {
            // HEAD: identical headers, empty body.
            call.respondStream(head) { }
            return
        }
        call.respondStream(head) { sink -> streamBody(asset, sink) }
    }

    /** Headers echoed back on a `304` reply — the validators the client matched on. */
    private fun conditionalHeaders(asset: Asset): HttpHeaders {
        val headers = HttpHeaders()
        asset.etag?.let { headers[HttpHeaderName.ETAG] = it }
        asset.lastModified?.let { headers[HttpHeaderName.LAST_MODIFIED] = formatHttpDate(it) }
        return headers
    }

    /** Streams the whole asset body into [sink] in [STREAM_CHUNK_SIZE]-sized chunks. */
    private suspend fun streamBody(asset: Asset, sink: HttpResponseBodySink) {
        val source = asset.open()
        try {
            val buffer = Buffer()
            while (true) {
                val read = source.readAtMostTo(buffer, STREAM_CHUNK_SIZE.toLong())
                if (read <= 0L) break
                val bytes = buffer.readByteArray()
                val ioBuf = DefaultAllocator.allocate(bytes.size)
                ioBuf.writeByteArray(bytes, 0, bytes.size)
                sink.write(ioBuf)
            }
        } finally {
            source.close()
        }
    }

    private companion object {

        /**
         * Evaluates the conditional-GET preconditions against [asset].
         * `If-None-Match` (weak comparison, `*` and comma list supported)
         * takes precedence over `If-Modified-Since` per RFC 9110 §13.1.3.
         */
        fun isNotModified(call: HttpCall, asset: Asset): Boolean {
            val ifNoneMatch = call.headers[HttpHeaderName.IF_NONE_MATCH]
            if (ifNoneMatch != null) {
                return matchesIfNoneMatch(ifNoneMatch, asset.etag)
            }
            val ifModifiedSince = call.headers[HttpHeaderName.IF_MODIFIED_SINCE] ?: return false
            val lastModified = asset.lastModified ?: return false
            val since = parseHttpDate(ifModifiedSince) ?: return false
            // Not modified when the asset is no newer than the client's copy.
            return lastModified.epochSeconds <= since.epochSeconds
        }

        /**
         * True when the `If-None-Match` field value matches [etag].
         *
         * `*` matches any existing representation; otherwise each
         * comma-separated entry is compared weakly (the `W/` prefix is
         * stripped from both sides, then the opaque tags must be equal).
         */
        fun matchesIfNoneMatch(fieldValue: String, etag: String?): Boolean {
            val trimmed = fieldValue.trim()
            if (trimmed == "*") return etag != null
            val assetTag = etag ?: return false
            val assetOpaque = weakOpaque(assetTag)
            for (entry in trimmed.split(',')) {
                if (weakOpaque(entry.trim()) == assetOpaque) return true
            }
            return false
        }

        /** Strips the optional `W/` weak prefix, leaving the quoted opaque tag. */
        fun weakOpaque(tag: String): String =
            if (tag.startsWith("W/")) tag.substring(2) else tag

        /**
         * Percent-decodes [value] exactly once (UTF-8). Returns null when
         * the input contains a malformed `%` escape — the serve layer
         * answers a malformed path with `404`.
         */
        fun percentDecode(value: String): String? {
            if (value.indexOf('%') < 0) return value
            val out = ArrayList<Byte>(value.length)
            var i = 0
            while (i < value.length) {
                val ch = value[i]
                if (ch == '%') {
                    if (i + 2 >= value.length) return null
                    val hi = hexValue(value[i + 1])
                    val lo = hexValue(value[i + 2])
                    if (hi < 0 || lo < 0) return null
                    out.add(((hi shl 4) or lo).toByte())
                    i += 3
                } else {
                    for (b in ch.toString().encodeToByteArray()) out.add(b)
                    i++
                }
            }
            return out.toByteArray().decodeToString()
        }

        /** Hex digit value, or -1 when [c] is not a hex digit. */
        fun hexValue(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
    }
}
