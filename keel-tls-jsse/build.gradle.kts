plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":keel-tls"))
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":keel-engine-nio"))
                implementation(project(":keel-codec-http"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
