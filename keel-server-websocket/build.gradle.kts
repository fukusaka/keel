plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()
    js(IR) { nodejs() }
    linuxX64()
    linuxArm64()
    macosArm64()
    macosX64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":keel-server-http"))
                api(project(":keel-codec-websocket"))
                // SPI only — permessage-deflate works against the
                // CompressionCodec abstraction; the concrete zlib
                // backend is supplied by the application, never a
                // hard dependency of this module (RFC 7692 §7).
                api(project(":keel-compression"))
                implementation(project(":keel-codec-http"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":keel-testing-internal"))
                // Concrete zlib backend for permessage-deflate tests only.
                implementation(project(":keel-compression-zlib"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        jvmTest {
            dependencies {
                // Real-engine integration test for the `webSockets { }` DSL.
                implementation(project(":keel-engine-nio"))
            }
        }
    }
}
