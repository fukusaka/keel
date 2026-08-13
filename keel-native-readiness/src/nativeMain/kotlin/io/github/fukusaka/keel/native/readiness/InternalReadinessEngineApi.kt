package io.github.fukusaka.keel.native.readiness

/**
 * Marks this module's surface, which exists for the two readiness engines and
 * for nobody else.
 *
 * Both were lifted out of the same two loops, and both are `public` for one
 * reason — those loops are in other Gradle modules, and Kotlin's `internal`
 * does not cross that boundary — and
 * neither is meant for callers outside them. A `Registration` carries another
 * subsystem's continuation; `cancelAll` fails every waiter on a file descriptor
 * whether or not the caller owns it; `markFinished` / `markQuiescent` move a
 * live loop's shutdown state. All of it assumes the caller knows which thread
 * it is on.
 *
 * Opting in is a deliberate, auditable "I am one of the POSIX readiness
 * engines" declaration, the same shape `UnsafeIoBufApi` uses one module down.
 */
@RequiresOptIn(
    message = "Internal surface of the readiness engines. Opt in only from the engines " +
        "that extend AbstractPosixReadinessEventLoop, or from their tests.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class InternalReadinessEngineApi
