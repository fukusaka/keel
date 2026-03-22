package io.github.fukusaka.keel.codec.http

/**
 * Well-known HTTP header field name constants (RFC 7230 §5, RFC 7231 §5/§7, RFC 6265).
 * Header names are case-insensitive (RFC 7230 §3.2), but conventionally Title-Case.
 */
object HttpHeaderName {
    // General headers (RFC 7230)
    const val CACHE_CONTROL     = "Cache-Control"
    const val CONNECTION        = "Connection"
    const val DATE              = "Date"
    const val TRAILER           = "Trailer"
    const val TRANSFER_ENCODING = "Transfer-Encoding"
    const val UPGRADE           = "Upgrade"
    const val VIA               = "Via"

    // Request headers (RFC 7231 §5)
    const val ACCEPT              = "Accept"
    const val ACCEPT_CHARSET      = "Accept-Charset"
    const val ACCEPT_ENCODING     = "Accept-Encoding"
    const val ACCEPT_LANGUAGE     = "Accept-Language"
    const val AUTHORIZATION       = "Authorization"
    const val EXPECT              = "Expect"
    const val HOST                = "Host"
    const val IF_MATCH            = "If-Match"
    const val IF_MODIFIED_SINCE   = "If-Modified-Since"
    const val IF_NONE_MATCH       = "If-None-Match"
    const val IF_RANGE            = "If-Range"
    const val IF_UNMODIFIED_SINCE = "If-Unmodified-Since"
    const val RANGE               = "Range"
    const val REFERER             = "Referer"
    const val TE                  = "TE"
    const val USER_AGENT          = "User-Agent"

    // Response headers (RFC 7231 §7)
    const val ACCEPT_RANGES      = "Accept-Ranges"
    const val AGE                = "Age"
    const val ALLOW              = "Allow"
    const val CONTENT_ENCODING   = "Content-Encoding"
    const val CONTENT_LANGUAGE   = "Content-Language"
    const val CONTENT_LENGTH     = "Content-Length"
    const val CONTENT_LOCATION   = "Content-Location"
    const val CONTENT_RANGE      = "Content-Range"
    const val CONTENT_TYPE       = "Content-Type"
    const val ETAG               = "ETag"
    const val EXPIRES            = "Expires"
    const val LAST_MODIFIED      = "Last-Modified"
    const val LOCATION           = "Location"
    const val PROXY_AUTHENTICATE = "Proxy-Authenticate"
    const val RETRY_AFTER        = "Retry-After"
    const val SERVER             = "Server"
    const val SET_COOKIE         = "Set-Cookie"   // RFC 6265 — must NOT be comma-joined
    const val VARY               = "Vary"
    const val WWW_AUTHENTICATE   = "WWW-Authenticate"
}
