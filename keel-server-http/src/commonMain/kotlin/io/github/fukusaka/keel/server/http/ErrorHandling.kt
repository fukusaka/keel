package io.github.fukusaka.keel.server.http

import kotlin.reflect.KClass

/**
 * One registered exception-to-response mapping: the exception [type] and
 * the [handler] that turns a matching throwable into a response.
 *
 * The handler's throwable parameter is erased to `Throwable`; the public
 * `exception<T>` DSL ([KeelHttpServerBuilder.exception]) casts it back to
 * `T` behind a checked `isInstance` match.
 */
internal class ExceptionMapper(
    val type: KClass<out Throwable>,
    val handler: suspend (HttpCall, Throwable) -> Unit,
)

/**
 * Server-level error handling: an optional [notFound] handler replacing
 * the built-in `404`, and ordered [exceptionMappers] consulted when a
 * request handler throws an exception that escapes the middleware chain.
 *
 * Built by [KeelHttpServerBuilder]; [DEFAULT] is the no-customisation
 * value (built-in `404` / `500` only).
 */
internal class ErrorHandlers(
    val notFound: RouteHandler?,
    val exceptionMappers: List<ExceptionMapper>,
) {

    /**
     * The first registered mapper whose type matches [cause], or null.
     * Registration order is priority — register specific types first.
     */
    fun mapperFor(cause: Throwable): ExceptionMapper? =
        exceptionMappers.firstOrNull { it.type.isInstance(cause) }

    internal companion object {
        /** No customisation — the built-in `404` / `500` responses only. */
        val DEFAULT: ErrorHandlers = ErrorHandlers(notFound = null, exceptionMappers = emptyList())
    }
}
