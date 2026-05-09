plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// keel-testing-internal holds shared test-only infrastructure used across
// multiple keel modules' test source sets — fixtures intended for keel's
// own test suite, not for users writing applications on top of keel (a
// future `keel-testing` umbrella module is reserved for that).
//
// The module is NOT published to Maven (no maven-publish plugin) and NOT
// included in the Dokka publication (see root build.gradle.kts — this
// module is intentionally absent from the `dokka(project(...))` list).
//
// Single internal-test-fixtures module — sibling-free for now: this module
// hosts the canonical fixtures for every layer (target-agnostic in
// commonMain, JDK-only in jvmMain, POSIX socket fakes in nativeMain). The
// earlier `keel-testing-common` (PR #488 / #489) and `keel-native-posix-testing`
// were merged into this single module in PR #490 because (a) the target
// sets had become identical after `keel-testing-common` went multiplatform
// in PR #489, (b) the `-common` suffix in `keel-testing-common` clashed
// with KMP's commonMain reading once nativeMain content joined, and (c)
// having one bucket module avoids future rename churn.
//
// Naming: `-internal` qualifier signals "for keel project test infra",
// not Kotlin's `internal` visibility keyword. The suffix reserves the
// qualifier-free name `keel-testing` for a future user-facing umbrella
// (e.g. an in-memory keel engine for application-level tests, mock-friendly
// `IoEngine` / `Channel`, etc.) that has no parallel today but is
// anticipated.
//
// Source set layout:
//   - commonMain: target-agnostic fixtures (`TestIoTransport`)
//   - jvmMain:    JDK-only fixtures (`TestHttpClient` for JDK 21
//                 `java.net.http.HttpClient` lifecycle discipline)
//   - nativeMain: POSIX socket Native fakes (`FakeNativeSocket` /
//                 `FakeNativeSocketOps` / `PosixRawClient`) +
//                 `posix_testing` cinterop
//   - nativeTest: in-module sanity tests for the Native fakes
//                 (`FakeNativeSocketTest`).
//
// History (consolidation):
//   - PR #488: `TestHttpClient` + `newTestHttpClient` (JVM-only)
//     consolidated four copies in keel-server-ktor / keel-server-ktor-cio
//     jvmTest + inline `TestWsClient` from NettyPipelineWsEchoTest /
//     NettyPipelineWsStressTest. Module landed as `keel-testing-common`.
//   - PR #489: `TestIoTransport` (commonMain) consolidated six copies
//     across keel-core / keel-tls / keel-codec-http / keel-codec-websocket /
//     keel-server-ktor-cio commonTest + keel-engine-netty jvmTest. The two
//     codec copies used `retain` semantics that violated
//     `AbstractIoTransport.write`'s ownership-transfer contract; the
//     canonical version restored ownership-transfer. Module went
//     multiplatform.
//   - PR #490 (this): renamed `keel-testing-common` → `keel-testing-internal`
//     and merged `keel-native-posix-testing` (4 native fakes + cinterop) into
//     this module's `nativeMain` + `nativeTest`. Reserved the qualifier-free
//     `keel-testing` slot for a future user-facing umbrella.
//
// Future scope (separate PRs):
//   - `WsEchoHandler` / `WsSeamContext` (keel-engine-netty jvmTest test
//     fixtures): can move into `jvmMain` here once a justification for
//     cross-test-class reuse appears beyond `keel-engine-netty` itself.

kotlin {
    // Targets mirror the union of fixture consumers: keel-core /
    // keel-codec-* / keel-tls / keel-server-ktor-cio commonTest (jvm + 4
    // native + jsNode), keel-engine-{epoll,io-uring,kqueue,nwconnection}
    // nativeTest (4 native, sourced from former posix-testing scope), and
    // keel-engine-netty / keel-server-ktor / keel-server-ktor-cio jvmTest
    // (jvm).
    jvm()
    linuxX64 {
        // POSIX socket testing cinterop: bindings for socketpair, etc.
        // used by `FakeNativeSocket` / `PosixRawClient` to drive scripted
        // in-memory or loopback POSIX sockets without going through the
        // production `keel-native-posix` cinterop (which targets
        // production-grade socket interactions, not test scripting).
        compilations["main"].cinterops {
            create("posix_testing") {
                defFile("src/nativeInterop/cinterop/posix_testing.def")
            }
        }
    }
    linuxArm64 {
        compilations["main"].cinterops {
            create("posix_testing") {
                defFile("src/nativeInterop/cinterop/posix_testing.def")
            }
        }
    }
    macosArm64 {
        compilations["main"].cinterops {
            create("posix_testing") {
                defFile("src/nativeInterop/cinterop/posix_testing.def")
            }
        }
    }
    macosX64 {
        compilations["main"].cinterops {
            create("posix_testing") {
                defFile("src/nativeInterop/cinterop/posix_testing.def")
            }
        }
    }
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
                // `api` because `TestIoTransport` extends `AbstractIoTransport`
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
        nativeMain {
            dependencies {
                // `api` so consumers (engine-epoll / engine-io-uring /
                // engine-kqueue / engine-nwconnection nativeTest) can
                // reference `NativeSocket` / `NativeSocketOps` interfaces
                // and sealed result types directly without listing
                // `:keel-native-posix` as a separate dep.
                api(project(":keel-native-posix"))
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
