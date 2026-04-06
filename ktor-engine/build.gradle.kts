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
                implementation(project(":core"))
                implementation(project(":tls"))
                implementation(project(":codec-http"))
                implementation(libs.ktor.server.core)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        jvmMain {
            dependencies {
                implementation(project(":engine-nio"))
            }
        }
        macosMain {
            dependencies {
                implementation(project(":engine-kqueue"))
            }
        }
        linuxMain {
            dependencies {
                implementation(project(":engine-epoll"))
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
            implementation(project(":tls-jsse"))
        }
    }
}
