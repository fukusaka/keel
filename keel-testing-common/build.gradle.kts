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
// Initial scope (PR #488): consolidate `TestHttpClient` + factory function
// from keel-server-ktor / keel-server-ktor-cio jvmTest and the inline
// `TestWsClient` private classes inside keel-engine-netty's
// NettyPipelineWsEchoTest / NettyPipelineWsStressTest. These four copies
// have identical shape — an `AutoCloseable` wrapper around `HttpClient` +
// `Executors.newFixedThreadPool(N)` daemon executor — and serve identical
// purposes (deterministic teardown of JDK 21 HttpClient selector +
// executor threads to avoid zombie-thread accumulation across the test
// suite, which on resource-constrained runners pushed later tests towards
// timeout budgets).
//
// Future scope (separate PRs):
//   - `TestIoTransport`: 6 copies across keel-core / keel-tls /
//     keel-codec-http / keel-codec-websocket / keel-server-ktor-cio /
//     keel-engine-netty, with two divergent write semantics
//     (retain vs ownership-transfer) that must be reconciled before
//     consolidation.
//   - `WsEchoHandler` / `WsSeamContext`: keel-engine-netty-specific
//     fixtures, can move once this module proves stable as a host for
//     multiplatform fixtures.

kotlin {
    // Only the JVM target for now — `TestHttpClient` is JVM-only because
    // it wraps `java.net.http.HttpClient`. Add multiplatform targets when
    // the first commonMain fixture (likely the unified `TestIoTransport`)
    // lands.
    jvm()

    sourceSets {
        jvmMain {
            // No production deps: TestHttpClient only wraps the JDK 21
            // `java.net.http.HttpClient` + `java.util.concurrent.Executors`,
            // both available on the standard library classpath.
        }
    }
}
