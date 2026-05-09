plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// keel-testing-common holds target-agnostic test-only infrastructure
// shared across multiple modules' test source sets. Pattern follows
// keel-native-posix-testing: NOT published to Maven (no maven-publish
// plugin) and NOT included in the Dokka publication (see root
// build.gradle.kts — this module is intentionally absent from the
// `dokka(project(...))` list).
//
// Naming convention: the `keel-testing-` prefix groups all test-helper
// modules. Sibling: `keel-native-posix-testing` (POSIX socket Native
// fakes + cinterop). Future siblings if scope demands them:
// `keel-testing-codec`, `keel-testing-engine`, etc.
// `keel-native-posix-testing` itself will rename to
// `keel-testing-native-posix` in a separate cleanup once the fixture-
// consolidation roadmap settles.
//
// Scope so far:
//   - PR #488: `TestHttpClient` + `newTestHttpClient` (JVM-only)
//     consolidating four copies in keel-server-ktor / keel-server-ktor-cio
//     jvmTest + inline `TestWsClient` from NettyPipelineWsEchoTest /
//     NettyPipelineWsStressTest.
//   - This PR: `TestIoTransport` (`commonMain`) consolidating six copies
//     across keel-core / keel-tls / keel-codec-http / keel-codec-websocket /
//     keel-server-ktor-cio commonTest + keel-engine-netty jvmTest.
//     The two codec copies used `retain` semantics that violated
//     `AbstractIoTransport.write`'s ownership-transfer contract; this
//     canonical version restores ownership-transfer.
//
// Future scope (separate PRs):
//   - `WsEchoHandler` / `WsSeamContext`: keel-engine-netty-specific
//     fixtures, can move once a `jvmMain` extension is justified.

kotlin {
    // Multiplatform target shape covers the union of test-fixture consumer
    // modules' targets so a single `commonMain` fixture (`TestIoTransport`)
    // can be imported uniformly from any consumer's `commonTest` /
    // `jvmTest`. Targets mirror keel-core / keel-codec-* / keel-tls /
    // keel-server-ktor-cio (JS Node.js included; macOS x64 included for
    // dokka publication parity even though it has no production
    // consumers).
    jvm()
    linuxX64()
    linuxArm64()
    macosArm64()
    macosX64()
    js(IR) {
        nodejs()
    }

    applyDefaultHierarchyTemplate()

    compilerOptions {
        // Required for `expect class` / `actual class` on KMP — kept in
        // sync with keel-core. Not used in the current scope (the
        // commonMain fixtures are pure-Kotlin), but flipped on so that
        // future additions don't trip the compiler default.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain {
            dependencies {
                // `api` because TestIoTransport extends `AbstractIoTransport`
                // and exposes `IoBuf` / `BufferAllocator` types that
                // consumers reference directly when constructing or
                // inspecting captured outbound buffers.
                api(project(":keel-core"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        jvmMain {
            // No additional deps: `TestHttpClient` only wraps the JDK 21
            // `java.net.http.HttpClient` + `java.util.concurrent.Executors`,
            // both available on the standard library classpath.
        }
    }
}
