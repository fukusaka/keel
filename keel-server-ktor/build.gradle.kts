plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // No platform-specific source sets: the Ktor adapter is engine-neutral,
    // it does not own per-platform engine wiring (the caller passes
    // `engine = ...` via the configuration block). Targets cover JVM + all
    // native platforms keel supports; JS is excluded because Ktor's
    // `BaseApplicationEngine` synchronous `start/stop` API depends on
    // `runBlocking`, which Kotlin/JS does not provide.
    jvm()
    linuxX64()
    linuxArm64()
    macosArm64()
    macosX64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":keel-core"))
                implementation(project(":keel-tls"))
                implementation(project(":keel-server"))
                // `api` because :keel-server-ktor-base exposes the
                // `KeelApplicationEngine` + `Configuration` types that
                // consumers of `embeddedServer(Keel)` reference directly.
                api(project(":keel-server-ktor-base"))
                implementation(project(":keel-codec-http"))
                // `api` because the WebSocket DSL (`keelWebSocket`) takes a
                // `WsSession` whose methods expose `WsFrame` / `WsCloseCode`
                // from `:keel-codec-websocket` — consumers see those types.
                api(project(":keel-codec-websocket"))
                // `api` because `keelWebSocket` exposes `WsSession` from the
                // shared `:keel-server-websocket` module (the WS session core
                // + RFC 6455 upgrade, reused by `:keel-server-http` too).
                api(project(":keel-server-websocket"))
                implementation(libs.ktor.server.core)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                // Shared test fixtures (`TestIoTransport`, `InjectedFault`) for
                // the pipeline-level handler tests, which run on every target.
                implementation(project(":keel-testing-internal"))
            }
        }
        jvmTest {
            dependencies {
                // Pin :keel-engine-nio and :keel-engine-netty for the Ktor
                // adapter's HTTP/HTTPS test suite — the adapter itself is
                // engine-neutral, but tests need concrete `StreamEngine`
                // instances to run against.
                implementation(project(":keel-engine-nio"))
                implementation(project(":keel-engine-netty"))
                implementation(project(":keel-tls-jsse"))
                // Shared test fixtures (`TestHttpClient` + `newTestHttpClient`).
                implementation(project(":keel-testing-internal"))
                // Standard Ktor WebSocket plugin for respondUpgrade tests.
                implementation(libs.ktor.server.websockets)
            }
        }
    }
}
