package io.github.fukusaka.keel.native.readiness

/**
 * Marks this module's surface, which exists for the two readiness engines and
 * for nobody else.
 *
 * Every declaration that carries this would be `internal` if the engines were
 * in this module. They are not — an engine's readiness primitive needs headers
 * only one host has, so each is its own Gradle module — and Kotlin's `internal`
 * does not cross that boundary. The marker is what `internal` cannot say here,
 * and it says it less well: `internal` is checked, an opt-in is declared.
 *
 * **Most of the members it marks are there for tests.** Seven types are behind
 * it, and twelve members. Of the members the engines' production code reaches
 * three — the loop's `cleanupFd`, its participant count, and the group's thread
 * resolution; two more are used by the engine base within this module. The
 * remaining seven are probes their seam tests ask about a connection they have
 * just torn down.
 * That is worth knowing before treating this surface as a design: it is mostly
 * the cost of testing an implementation from the module next door.
 */
@RequiresOptIn(
    message = "Internal surface of the readiness engines. Opt in only from the engines " +
        "that extend AbstractReadinessEventLoop, or from their tests.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class InternalReadinessEngineApi
