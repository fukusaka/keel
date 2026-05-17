package io.github.fukusaka.keel.testing.server.http

import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpStatus

/**
 * The response a [KeelHttpTestClient] parsed off the wire after driving a
 * request through the in-process HTTP server pipeline.
 *
 * @property status the response status line's [HttpStatus].
 * @property headers the response header fields.
 */
public class TestHttpResponse internal constructor(
    public val status: HttpStatus,
    public val headers: HttpHeaders,
    private val body: ByteArray,
) {

    /** The response body as raw bytes (a defensive copy). */
    public fun bodyBytes(): ByteArray = body.copyOf()

    /** The response body decoded as UTF-8 text. */
    public fun bodyText(): String = body.decodeToString()

    override fun toString(): String =
        "TestHttpResponse(status=$status, headers=$headers, body=${body.size} bytes)"
}
