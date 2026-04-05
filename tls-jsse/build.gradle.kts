plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":tls"))
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":logging"))
                implementation(project(":engine-nio"))
                implementation(project(":codec-http"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
