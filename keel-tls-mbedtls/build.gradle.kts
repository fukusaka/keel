plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val hostOs: String = System.getProperty("os.name").lowercase()
val isMacHost: Boolean = hostOs.contains("mac")
val isLinuxHost: Boolean = hostOs.contains("linux")

kotlin {
    macosArm64 {
        compilations["main"].cinterops {
            create("mbedtls") {
                defFile("src/nativeInterop/cinterop/mbedtls.def")
            }
        }
    }
    macosX64 {
        compilations["main"].cinterops {
            create("mbedtls") {
                defFile("src/nativeInterop/cinterop/mbedtls.def")
            }
        }
    }
    linuxX64 {
        compilations["main"].cinterops {
            create("mbedtls") {
                defFile("src/nativeInterop/cinterop/mbedtls.def")
            }
        }
    }
    linuxArm64 {
        compilations["main"].cinterops {
            create("mbedtls") {
                defFile("src/nativeInterop/cinterop/mbedtls.def")
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
        val nativeTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":keel-codec-http"))
                implementation(project(":keel-server"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        // Cross-backend regression source set: drives an in-memory MbedTLS
        // server ↔ OpenSSL client handshake (the only way to complete a
        // real MbedTLS *server* handshake in-process — the MbedTLS factory
        // does not wire the client-side hostname MbedTLS verification
        // requires). Shared only by the targets where keel-tls-openssl is
        // built (macosArm64 + linuxX64); macosX64 / linuxArm64 keep the
        // plain nativeTest coverage. Uses only OpenSslCodecFactory's pure
        // Kotlin API, so no openssl cinterop commonization is needed.
        val opensslPeerTest by creating {
            dependsOn(nativeTest)
            dependencies {
                implementation(project(":keel-tls-openssl"))
            }
        }
        val macosArm64Test by getting { dependsOn(opensslPeerTest) }
        val linuxX64Test by getting { dependsOn(opensslPeerTest) }
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
