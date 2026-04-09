plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val hostOs: String = System.getProperty("os.name").lowercase()

kotlin {
    jvm()
    if (hostOs.contains("mac")) {
        macosArm64()
        macosX64()
    }
    if (hostOs.contains("linux")) {
        linuxX64()
        linuxArm64()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":keel-core"))
                implementation(project(":keel-tls"))
                implementation(project(":keel-codec-http"))
                implementation(libs.ktor.server.core)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        jvmMain {
            dependencies {
                implementation(project(":keel-engine-nio"))
            }
        }
        macosMain {
            dependencies {
                implementation(project(":keel-engine-kqueue"))
            }
        }
        linuxMain {
            dependencies {
                implementation(project(":keel-engine-epoll"))
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
                implementation(project(":keel-tls-jsse"))
            }
        }
    }
}
