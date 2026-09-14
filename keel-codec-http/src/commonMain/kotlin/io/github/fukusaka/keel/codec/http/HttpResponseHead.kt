package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.Releasable

/**
 * HTTP/1.1 response head — status line + headers, without the body (RFC 7230 §3).
 *
 * Use [parseResponseHead] to obtain an instance from a [kotlinx.io.Source].
 * The body bytes remain in the source for streaming consumption.
 */
data class HttpResponseHead(
    val status: HttpStatus,
    val version: HttpVersion = HttpVersion.HTTP_1_1,
    val headers: HttpHeaders = HttpHeaders(),
) : HttpMessage, Releasable {
    // Which borrow of [headers] this message was built on. A body property, so
    // it is not part of equals / hashCode / copy's parameters; a copy reads the
    // same value from the same headers and the two share one release.
    private val headersLease: Int = headers.currentLease

    /**
     * Returns the pooled [headers] and the receive buffer their values view.
     *
     * Returns `true` on the call that gave them back and `false` on every
     * other: a second call, a call on a copy of a message already released,
     * a call made after the pool lent the same headers to another message,
     * and a call on a message whose headers were built directly and own
     * nothing. None of those throws, and none returns headers another
     * message is using.
     *
     * A [io.github.fukusaka.keel.pipeline.TypedInboundHandler] that takes
     * this type releases it when its callback returns; use [detach] to keep
     * the message beyond that.
     */
    override fun release(): Boolean = headers.releaseLease(headersLease)

    /**
     * Returns a copy that owns its headers, and releases this message.
     *
     * The copy's [headers] hold `String` values on the heap, so it stays
     * valid for as long as it is referenced and needs no release. This is
     * how a handler keeps a received message past the call that delivered
     * it. A message whose headers own nothing is returned as it is.
     *
     * @throws IllegalStateException if this message was already released.
     */
    fun detach(): HttpResponseHead {
        if (!headers.isPooled) return this
        check(headers.holdsLease(headersLease)) { "HttpResponseHead was already released" }
        val detached = copy(headers = headers.heapCopy())
        headers.releaseLease(headersLease)
        return detached
    }
}
