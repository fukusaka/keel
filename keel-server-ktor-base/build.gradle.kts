plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // No platform-specific source sets: this module is the codec-agnostic
    // skeleton for keel's Ktor adapters. Codec-specific impls live in
    // sibling modules (:keel-server-ktor for keel's codec-http,
    // :keel-server-ktor-cio for ktor-http-cio). JS is excluded because
    // Ktor's `BaseApplicationEngine` synchronous `start/stop` API depends
    // on `runBlocking`, which Kotlin/JS does not provide.
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
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
