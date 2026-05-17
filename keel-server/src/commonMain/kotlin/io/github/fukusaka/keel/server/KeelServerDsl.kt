package io.github.fukusaka.keel.server

/**
 * [DslMarker] for keel server configuration DSLs.
 *
 * Builders across the keel server stack — `connector { }` / `tls { }` here,
 * and `keelHttpServer { }` / future protocol builders downstream — carry
 * this marker so that, in a nested block, only the innermost builder's
 * members are implicitly in scope. Without it, an inner block such as
 * `connector { }` could accidentally call an outer `keelHttpServer { }`
 * member; the marker turns such cross-scope calls into compile errors.
 */
@DslMarker
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class KeelServerDsl
