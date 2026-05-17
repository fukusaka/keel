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
                implementation(project(":keel-codec-http"))
                implementation(libs.kotlinx.coroutines.core)
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
                // Real-engine integration test for the `webSocket { }` DSL.
                implementation(project(":keel-engine-nio"))
            }
        }
    }
}
