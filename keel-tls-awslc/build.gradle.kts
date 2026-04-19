plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val hostOs: String = System.getProperty("os.name").lowercase()
val isMacHost: Boolean = hostOs.contains("mac")
val isLinuxHost: Boolean = hostOs.contains("linux")

kotlin {
    macosArm64 {
        compilations["main"].cinterops {
            create("awslc") {
                defFile("src/nativeInterop/cinterop/awslc.def")
            }
        }
    }
    linuxX64 {
        compilations["main"].cinterops {
            create("awslc") {
                defFile("src/nativeInterop/cinterop/awslc.def")
            }
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":keel-tls"))
            }
        }
        nativeTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":keel-codec-http"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        // Gate per-target test wiring on the host flag: the referenced
        // engine modules (:keel-engine-kqueue / :keel-engine-epoll) are
        // host-gated in `settings.gradle.kts` so they only exist in the
        // build on a matching host.
        if (isMacHost) {
            val macosTest by getting {
                dependencies {
                    implementation(project(":keel-engine-kqueue"))
                }
            }
        }
        if (isLinuxHost) {
            val linuxTest by getting {
                dependencies {
                    implementation(project(":keel-engine-epoll"))
                }
            }
        }
    }
}
