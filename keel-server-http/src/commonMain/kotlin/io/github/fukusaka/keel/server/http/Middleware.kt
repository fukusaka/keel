package io.github.fukusaka.keel.server.http

/**
 * A middleware stage wrapping request dispatch.
 *
 * Middleware forms a chain in front of the matched [RouteHandler]: each
 * stage receives the [HttpCall] and a `next` continuation, does work
 * before and/or after invoking `next`, and may skip `next` entirely to
 * short-circuit the request — for example an auth check that responds
 * `401` itself.
 *
 * Call `next` exactly once to run the rest of the chain — the remaining
 * middleware and ultimately the route handler (or the `404` terminal) —
 * or skip it to short-circuit, in which case the middleware must itself
 * produce the response via `call.respond*`.
 *
 * The chain runs for **every** request — including one whose path
 * matched no route — so a logging or error-handling middleware observes
 * `404`s too. The route is resolved before the chain runs; the innermost
 * `next` then invokes the matched handler, or emits `404 Not Found` when
 * nothing matched.
 *
 * Middleware runs in registration order: the first one installed is the
 * outermost. Each is registered with `install` on the `keelHttpServer { }`
 * builder.
 *
 * `Middleware` is a `fun interface`, so a plain lambda works:
 *
 * ```
 * keelHttpServer(engine) {
 *     install { call, next ->
 *         val start = TimeSource.Monotonic.markNow()
 *         next()
 *         println("${call.method} ${call.path} — ${start.elapsedNow()}")
 *     }
 *     get("/hello") { call -> call.respondText("hi") }
 * }
 * ```
 */
public fun interface Middleware {

    /**
     * Processes [call], invoking [next] to continue the chain.
     *
     * Declared `operator` so a middleware applies like a function:
     * `middleware(call) { next }`.
     */
    public suspend operator fun invoke(call: HttpCall, next: suspend () -> Unit)
}
