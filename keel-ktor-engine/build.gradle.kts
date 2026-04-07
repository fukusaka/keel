plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()

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
    }
}

// TLS test dependencies — only available with -Ptls (tls-jsse is opt-in).
if (providers.gradleProperty("tls").isPresent) {
    kotlin.sourceSets.getByName("jvmTest") {
        dependencies {
            implementation(project(":keel-tls-jsse"))
        }
    }
}
