package io.github.fukusaka.keel.codec.http

/**
 * Materialises this response's pooled headers into a GC-owned [HttpHeaders]
 * (String values, detached from the recv buffer), runs [block] with that
 * detached copy, and releases the pooled headers in a `finally` — so a throw
 * mid-materialisation still fulfils the release contract. Returns whatever
 * [block] returns.
 *
 * The decoder borrows a response's [HttpHeaders] from a per-EventLoop-thread
 * pool and they view the retained recv buffer (`addRange`); the aggregator
 * relinquished the head at emit, so the consumer is their sole owner and must
 * copy the fields out while the buffer is still valid, then release the pooled
 * headers exactly once. Because the pool and buffer are per-EventLoop-thread,
 * this must run on the thread that owns them — keel consumers confine their
 * receive to the channel's `ioDispatcher` (the EventLoop thread), so the
 * release here stays on the buffer-owning thread even when a cancellation
 * originates elsewhere.
 *
 * [block] may still read the pooled `response.headers` (e.g. to decide
 * connection reuse) — they remain valid until it returns. The receiver
 * (`this`) is in scope at the call site, so [block] takes only the detached
 * copy.
 *
 * Centralises the materialise + release-in-finally invariant shared by the
 * HTTP client (`keel-client-http`) and the in-process test client
 * (`keel-testing-server-http`) — the seam whose cross-thread and
 * throw-mid-materialisation edges were the subject of earlier fixes.
 */
public inline fun <R> HttpResponse.materializeReleasingHeaders(block: (detached: HttpHeaders) -> R): R {
    val detached = HttpHeaders()
    try {
        headers.forEach { name, value -> detached.add(name, value) }
        return block(detached)
    } finally {
        headers.release()
    }
}
