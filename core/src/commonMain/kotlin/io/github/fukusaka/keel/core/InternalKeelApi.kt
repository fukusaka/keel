package io.github.fukusaka.keel.core

/**
 * Marks declarations that are internal to keel and should not be used
 * by external consumers. These APIs may change without notice.
 *
 * Usage within keel submodules (e.g., benchmark) requires explicit opt-in:
 * `@OptIn(InternalKeelApi::class)`
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "Internal keel API. Subject to change without notice.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
public annotation class InternalKeelApi
