package io.github.fukusaka.keel.client.http

/**
 * The parsed pieces of a request URL that the client needs: the connect
 * target ([host] / [port]), the request-line [target] (path plus optional
 * query), and the [authority] to put in the `Host` header.
 *
 * Only `http://` origin-form URLs are supported. `https://` is rejected
 * because client TLS is a later prerequisite (native-stack client TLS),
 * and any other scheme is malformed for this client.
 *
 * The parser is deliberately small — no userinfo, fragment, or relative
 * resolution semantics beyond what a fresh-connect GET/POST needs:
 * userinfo (`user:pass@`) is stripped and ignored, a fragment (`#...`) is
 * dropped, and a missing path becomes `/`.
 */
internal class RequestUrl private constructor(
    val host: String,
    val port: Int,
    val target: String,
    val authority: String,
) {
    companion object {
        private const val HTTP_PREFIX = "http://"
        private const val HTTPS_PREFIX = "https://"
        private const val DEFAULT_PORT = 80
        private const val MAX_PORT = 65535

        /**
         * Parses [url] into its connect target, request target, and Host
         * authority.
         *
         * @throws UnsupportedOperationException for an `https://` URL — the
         *   client is plaintext only.
         * @throws IllegalArgumentException for a non-`http` scheme, an empty
         *   host, or an invalid / out-of-range port.
         */
        fun parse(url: String): RequestUrl {
            val rest = when {
                url.startsWith(HTTP_PREFIX, ignoreCase = true) -> url.substring(HTTP_PREFIX.length)
                url.startsWith(HTTPS_PREFIX, ignoreCase = true) ->
                    throw UnsupportedOperationException(
                        "keel-client-http supports http:// only; https:// needs client TLS: $url",
                    )
                else -> throw IllegalArgumentException("URL must start with http://: $url")
            }

            // The authority runs up to the first '/', '?', or '#'; the rest is
            // the request target (and an optional fragment we drop).
            val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
            val authority = if (authorityEnd < 0) rest else rest.substring(0, authorityEnd)
            require(authority.isNotEmpty()) { "URL has no host: $url" }

            val afterAuthority = if (authorityEnd < 0) "" else rest.substring(authorityEnd)
            val pathAndQuery = afterAuthority.substringBefore('#')
            val target = when {
                pathAndQuery.isEmpty() -> "/"
                // A query with no path ("http://h?x=1") gets an implicit "/".
                pathAndQuery.startsWith('?') -> "/$pathAndQuery"
                else -> pathAndQuery
            }

            // Userinfo is accepted for compatibility but plays no part in L1.
            val hostPort = authority.substringAfterLast('@')
            require(hostPort.isNotEmpty()) { "URL has no host: $url" }
            val (host, port) = splitHostPort(hostPort, url)
            require(host.isNotEmpty()) { "URL has no host: $url" }

            // IPv6 literals are bracketed in the Host header; the port is
            // appended only when it differs from the default.
            val hostForHeader = if (host.contains(':')) "[$host]" else host
            val headerAuthority = if (port == DEFAULT_PORT) hostForHeader else "$hostForHeader:$port"
            return RequestUrl(host, port, target, headerAuthority)
        }

        private fun splitHostPort(hostPort: String, url: String): Pair<String, Int> {
            if (hostPort.startsWith('[')) {
                val close = hostPort.indexOf(']')
                require(close > 1) { "malformed IPv6 authority in URL: $url" }
                val host = hostPort.substring(1, close)
                val afterBracket = hostPort.substring(close + 1)
                val port = when {
                    afterBracket.isEmpty() -> DEFAULT_PORT
                    afterBracket.startsWith(':') -> parsePort(afterBracket.substring(1), url)
                    else -> throw IllegalArgumentException("malformed IPv6 authority in URL: $url")
                }
                return host to port
            }
            val colon = hostPort.lastIndexOf(':')
            return if (colon < 0) {
                hostPort to DEFAULT_PORT
            } else {
                hostPort.substring(0, colon) to parsePort(hostPort.substring(colon + 1), url)
            }
        }

        private fun parsePort(text: String, url: String): Int {
            val port = text.toIntOrNull()
                ?: throw IllegalArgumentException("invalid port '$text' in URL: $url")
            require(port in 1..MAX_PORT) { "port out of range ($port) in URL: $url" }
            return port
        }
    }
}
