package io.github.fukusaka.keel.native.posix

/**
 * Marks an API as test-only infrastructure that is exposed in the
 * production artifact solely because Kotlin/Multiplatform lacks a
 * stable `testFixtures` mechanism for Native targets. Callers must
 * acknowledge the intent by opting in:
 *
 * ```kotlin
 * @OptIn(InternalTestApi::class)
 * class MyEngineTest { ... }
 * ```
 *
 * ## What this annotation does NOT guarantee
 *
 * - **Not a security boundary**: the API is still `public` at the
 *   bytecode / klib level. A determined caller can reach it by
 *   propagating the opt-in; the annotation merely forces them to
 *   stop and read the KDoc.
 * - **Not a compatibility seal**: marked APIs may change signature
 *   or behaviour between keel minor releases without warning. Depend
 *   on them only from tests you control.
 *
 * ## Why opt-in rather than `internal`
 *
 * Kotlin's `internal` visibility is module-scoped, so sibling
 * modules (every `keel-engine-*` that wants to reuse
 * `PosixRawClient` / `FakeNativeSocket` in its tests) cannot see
 * `internal` symbols of `keel-native-posix`. The practical
 * alternatives are a separate `keel-native-posix-testing` artifact
 * (Option C, tracked for a future cleanup pass) and this opt-in
 * annotation (Option A, current); the latter is kept until the
 * project's publication surface warrants the extra module.
 *
 * @see PosixRawClient
 * @see FakeNativeSocket
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This API is test-only infrastructure and not covered by keel's public " +
        "compatibility guarantees. Opt in explicitly if you are writing a test.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
public annotation class InternalTestApi
