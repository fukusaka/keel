plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // Same target shape as :keel-server-ktor — engine-neutral adapter, JS
    // excluded for the same `runBlocking`-on-`BaseApplicationEngine` reason.
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
                // consumers of `embeddedServer(KeelCio)` reference.
                api(project(":keel-server-ktor-base"))
                implementation(libs.ktor.server.core)
                // Pattern C uses ktor-http-cio's parser instead of keel's
                // `:keel-codec-http`. parseRequest / parseHttpBody / CIOHeaders.
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
        jvmTest {
            dependencies {
                // Pin :keel-engine-nio for the integration test suite — the
                // adapter itself is engine-neutral, but tests need a concrete
                // `StreamEngine` to run against.
                implementation(project(":keel-engine-nio"))
            }
        }
    }
}
