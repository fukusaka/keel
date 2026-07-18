package io.github.fukusaka.keel.client.http.dsl

import io.github.fukusaka.keel.client.http.KeelHttpClient
import io.github.fukusaka.keel.core.StreamEngine

/**
 * Builder for [keelHttpClient].
 *
 * This builder currently exposes no configuration knobs — the client is
 * defined entirely by the injected [StreamEngine]. Default headers,
 * per-request timeouts, and connection-pool tuning arrive later; keeping
 * the builder now means those additions do not change the call shape.
 */
public class KeelHttpClientBuilder internal constructor() {

    internal fun build(engine: StreamEngine): KeelHttpClient = KeelHttpClient(engine)
}

/**
 * Builds a [KeelHttpClient] on [engine].
 *
 * The [engine] is owned by the caller and is never closed by the returned
 * client.
 *
 * ```
 * val client = keelHttpClient(engine)
 * val res = client.get("http://127.0.0.1:8080/hello")
 * println(res.status)        // HttpStatus(200)
 * println(res.bodyText())
 * ```
 */
public fun keelHttpClient(
    engine: StreamEngine,
    configure: KeelHttpClientBuilder.() -> Unit = {},
): KeelHttpClient = KeelHttpClientBuilder().apply(configure).build(engine)
