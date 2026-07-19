package io.github.fukusaka.keel.client.http

/**
 * A request exceeded the client's configured request timeout.
 *
 * Deliberately **not** a `CancellationException`: the timeout is a failure of
 * this request, not a cancellation of the caller's coroutine. Surfacing
 * `TimeoutCancellationException` would look like structured-concurrency
 * cancellation to the caller's scope and could be swallowed by a surrounding
 * `try { … } catch (e: CancellationException)`.
 *
 * A caller who cancels the request themselves (their own `withTimeout`, or their
 * scope being cancelled) still sees `CancellationException` as usual.
 */
public class HttpRequestTimeoutException internal constructor(
    url: String,
    timeoutMillis: Long,
    cause: Throwable? = null,
) : IllegalStateException("request to $url timed out after ${timeoutMillis}ms", cause)
