package io.github.keel.codec.http

/**
 * HTTP request method (RFC 7231 §4, RFC 5789).
 * method = token — case-sensitive; custom extension methods are allowed by RFC,
 * so this is a data class rather than an enum.
 *
 * Safe methods (RFC 7231 §4.2.1):    GET, HEAD, OPTIONS, TRACE
 * Idempotent methods (RFC 7231 §4.2.2): GET, HEAD, PUT, DELETE, OPTIONS, TRACE
 */
data class HttpMethod(val name: String) {

    init {
        require(name.isNotEmpty()) { "Method name must not be empty" }
        require(name.none { it.isWhitespace() }) { "Method name must not contain whitespace: $name" }
    }

    val isSafe: Boolean get() = this in SAFE_METHODS
    val isIdempotent: Boolean get() = this in IDEMPOTENT_METHODS

    override fun toString() = name

    companion object {
        val GET     = HttpMethod("GET")
        val HEAD    = HttpMethod("HEAD")
        val POST    = HttpMethod("POST")
        val PUT     = HttpMethod("PUT")
        val DELETE  = HttpMethod("DELETE")
        val CONNECT = HttpMethod("CONNECT")
        val OPTIONS = HttpMethod("OPTIONS")
        val TRACE   = HttpMethod("TRACE")
        val PATCH   = HttpMethod("PATCH")   // RFC 5789

        private val SAFE_METHODS       = setOf(GET, HEAD, OPTIONS, TRACE)
        private val IDEMPOTENT_METHODS = setOf(GET, HEAD, PUT, DELETE, OPTIONS, TRACE)
    }
}
