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
                api(project(":keel-core"))
                api(project(":keel-codec-http"))
                api(project(":keel-server"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.io.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":keel-testing-internal"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        jvmTest {
            dependencies {
                // Real-engine HTTPS integration tests for the connector layer.
                implementation(project(":keel-engine-nio"))
                implementation(project(":keel-engine-netty"))
                implementation(project(":keel-tls-jsse"))
            }
        }
    }
}
