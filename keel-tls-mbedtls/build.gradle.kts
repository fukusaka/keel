plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

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
        nativeTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":keel-codec-http"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val macosTest by getting {
            dependencies {
                implementation(project(":keel-engine-kqueue"))
            }
        }
        val linuxTest by getting {
            dependencies {
                implementation(project(":keel-engine-epoll"))
            }
        }
    }
}
