package io.github.fukusaka.keel.codec.http

/**
 * HTTP status code (RFC 7231 §6).
 * Status-Code = 3DIGIT — extension codes outside the standard set are permitted.
 * Reason-Phrase is informational only; clients MUST ignore it (RFC 7230 §3.1.2).
 */
data class HttpStatus(val code: Int) {

    init {
        require(code in 100..999) { "Invalid status code: $code (must be 100–999)" }
    }

    val isInformational: Boolean get() = code in 100..199
    val isSuccess:       Boolean get() = code in 200..299
    val isRedirection:   Boolean get() = code in 300..399
    val isClientError:   Boolean get() = code in 400..499
    val isServerError:   Boolean get() = code in 500..599

    fun reasonPhrase(): String = REASON_PHRASES[code] ?: "Unknown"

    override fun toString() = "$code ${reasonPhrase()}"

    companion object {
        // 1xx Informational
        val CONTINUE            = HttpStatus(100)
        val SWITCHING_PROTOCOLS = HttpStatus(101)

        // 2xx Success
        val OK              = HttpStatus(200)
        val CREATED         = HttpStatus(201)
        val ACCEPTED        = HttpStatus(202)
        val NO_CONTENT      = HttpStatus(204)
        val RESET_CONTENT   = HttpStatus(205)
        val PARTIAL_CONTENT = HttpStatus(206)

        // 3xx Redirection
        val MOVED_PERMANENTLY  = HttpStatus(301)
        val FOUND              = HttpStatus(302)
        val SEE_OTHER          = HttpStatus(303)
        val NOT_MODIFIED       = HttpStatus(304)
        val TEMPORARY_REDIRECT = HttpStatus(307)
        val PERMANENT_REDIRECT = HttpStatus(308)

        // 4xx Client Error
        val BAD_REQUEST            = HttpStatus(400)
        val UNAUTHORIZED           = HttpStatus(401)
        val FORBIDDEN              = HttpStatus(403)
        val NOT_FOUND              = HttpStatus(404)
        val METHOD_NOT_ALLOWED     = HttpStatus(405)
        val NOT_ACCEPTABLE         = HttpStatus(406)
        val REQUEST_TIMEOUT        = HttpStatus(408)
        val CONFLICT               = HttpStatus(409)
        val GONE                   = HttpStatus(410)
        val LENGTH_REQUIRED        = HttpStatus(411)
        val CONTENT_TOO_LARGE      = HttpStatus(413)
        val UNSUPPORTED_MEDIA_TYPE = HttpStatus(415)
        val EXPECTATION_FAILED     = HttpStatus(417)
        val IM_A_TEAPOT            = HttpStatus(418)
        val TOO_MANY_REQUESTS      = HttpStatus(429)

        // 5xx Server Error
        val INTERNAL_SERVER_ERROR      = HttpStatus(500)
        val NOT_IMPLEMENTED            = HttpStatus(501)
        val BAD_GATEWAY                = HttpStatus(502)
        val SERVICE_UNAVAILABLE        = HttpStatus(503)
        val GATEWAY_TIMEOUT            = HttpStatus(504)
        val HTTP_VERSION_NOT_SUPPORTED = HttpStatus(505)

        private val REASON_PHRASES = mapOf(
            100 to "Continue",
            101 to "Switching Protocols",
            200 to "OK",
            201 to "Created",
            202 to "Accepted",
            204 to "No Content",
            205 to "Reset Content",
            206 to "Partial Content",
            301 to "Moved Permanently",
            302 to "Found",
            303 to "See Other",
            304 to "Not Modified",
            307 to "Temporary Redirect",
            308 to "Permanent Redirect",
            400 to "Bad Request",
            401 to "Unauthorized",
            403 to "Forbidden",
            404 to "Not Found",
            405 to "Method Not Allowed",
            406 to "Not Acceptable",
            408 to "Request Timeout",
            409 to "Conflict",
            410 to "Gone",
            411 to "Length Required",
            413 to "Content Too Large",
            415 to "Unsupported Media Type",
            417 to "Expectation Failed",
            418 to "I'm a Teapot",
            429 to "Too Many Requests",
            500 to "Internal Server Error",
            501 to "Not Implemented",
            502 to "Bad Gateway",
            503 to "Service Unavailable",
            504 to "Gateway Timeout",
            505 to "HTTP Version Not Supported",
        )
    }
}
