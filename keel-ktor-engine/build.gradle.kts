plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val hostOs: String = System.getProperty("os.name").lowercase()
val isMacHost: Boolean = hostOs.contains("mac")
val isLinuxHost: Boolean = hostOs.contains("linux")

kotlin {
    // Native targets are host-gated because the transitive engine dependencies
    // (keel-engine-kqueue, keel-engine-epoll) require cinterop with platform-
    // specific headers that can only run on a matching host, and those engine
    // modules themselves are host-gated in `settings.gradle.kts` so they only
    // exist in the build on a matching host. Consumers such as `:benchmark`
    // mirror the same host gating so variant resolution stays consistent
    // across hosts.
    jvm()
    if (isMacHost) {
        macosArm64()
        macosX64()
    }
    if (isLinuxHost) {
        linuxX64()
        linuxArm64()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":keel-core"))
                implementation(project(":keel-tls"))
                implementation(project(":keel-server"))
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
        // Gate per-target source-set wiring on the host flag because the
        // referenced projects (:keel-engine-kqueue / :keel-engine-epoll)
        // only exist in the build on a matching host.
        if (isMacHost) {
            macosMain {
                dependencies {
                    implementation(project(":keel-engine-kqueue"))
                }
            }
        }
        if (isLinuxHost) {
            linuxMain {
                dependencies {
                    implementation(project(":keel-engine-epoll"))
                }
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
