package io.github.fukusaka.keel.client.http

/**
 * A request was redirected more times than the client's `maxRedirects` allows.
 *
 * The cap is what stops a redirect cycle (`/a` → `/b` → `/a`) from looping
 * forever; hitting it is reported as a failure rather than by returning one of
 * the intermediate 3xx responses, so a caller cannot mistake a truncated chain
 * for the resource.
 */
public class TooManyRedirectsException internal constructor(
    url: String,
    maxRedirects: Int,
) : IllegalStateException("request to $url exceeded $maxRedirects redirects")
