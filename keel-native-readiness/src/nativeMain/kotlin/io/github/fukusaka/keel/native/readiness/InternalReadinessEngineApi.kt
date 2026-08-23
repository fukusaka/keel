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
 * **Most of the members it marks are there for tests.** Eight types are behind
 * it, and twelve members. Of those members the engines' production code reaches
 * exactly two — the loop's `cleanupFd` and the engine's thread resolution. The
 * other ten are used inside this module, or are probes the engines' seam tests
 * ask about a connection they have just torn down, or both.
 * That is worth knowing before treating this surface as a design: it is mostly
 * the cost of testing an implementation from the module next door.
 *
 * **For the fault model, this is a boundary.** What it marks is keel's own
 * code, not a seam: the engines do not guard calls to it as though a third
 * party might have replaced the implementation. An opt-in cannot enforce that
 * — anyone willing to write the annotation can call in — and the alternative
 * is worse. Treating "public behind a marker" as a seam would make every
 * helper on that surface something to be defended against, which is how the
 * question of what can fail stops having an answer.
 */
@RequiresOptIn(
    message = "Internal surface of the readiness engines. Opt in only from the engines " +
        "that extend AbstractReadinessEventLoop, or from their tests.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class InternalReadinessEngineApi
