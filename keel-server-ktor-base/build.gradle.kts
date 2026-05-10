plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // The codec-agnostic skeleton for keel's Ktor adapters. Codec-specific
    // impls live in sibling modules (:keel-server-ktor for keel's codec-http,
    // :keel-server-ktor-cio for ktor-http-cio). JS is excluded because Ktor's
    // `BaseApplicationEngine` synchronous `start/stop` API depends on
    // `runBlocking`, which Kotlin/JS does not provide.
    //
    // Native-only `compression.KeelCompressionPlugin` lives in nativeMain —
    // ktor-server-compression is JVM-only, and on Native ktor's `GZipEncoder`
    // / `DeflateEncoder` are identity-only no-op stubs. The plugin closes the
    // gap by providing keel-compression-zlib backed encoders + a
    // RouteScopedPlugin that hooks into `ApplicationSendPipeline.ContentEncoding`.
    // JVM users continue to use `install(Compression)` from
    // ktor-server-compression unchanged.
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
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.http.cio)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.io.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        nativeMain {
            dependencies {
                // keel-compression SPI + zlib backend power the Native-only
                // `KeelCompressionPlugin` (ktor `Compression` plugin compatible).
                implementation(project(":keel-compression"))
                implementation(project(":keel-compression-zlib"))
                implementation(project(":keel-codec-http"))
            }
        }
    }
}
