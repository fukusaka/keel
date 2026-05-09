plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":keel-core"))
            }
        }
        jvmMain {
            dependencies {
                implementation(project(":keel-tls"))
                implementation(project(":keel-server"))
                implementation(libs.netty.all)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.io.core)
                implementation(project(":keel-codec-http"))
                implementation(project(":keel-codec-websocket"))
                implementation(project(":keel-io"))
            }
        }
    }
}
