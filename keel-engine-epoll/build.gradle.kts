plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    linuxX64 {
        compilations["main"].cinterops {
            create("epoll") {
                defFile("src/nativeInterop/cinterop/epoll.def")
            }
        }
    }
    linuxArm64 {
        compilations["main"].cinterops {
            create("epoll") {
                defFile("src/nativeInterop/cinterop/epoll.def")
            }
        }
    }

    // Creates linuxMain intermediate source set shared by linuxX64 and linuxArm64
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":keel-core"))
                implementation(project(":keel-native-posix"))
                implementation(project(":keel-native-readiness"))
            }
        }
        val linuxMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val linuxTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
                // FakeNativeSocket / FakeNativeSocketOps / PosixRawClient /
                // InternalTestApi were extracted from keel-native-posix's
                // nativeMain into this test-only module on 2026-04-23.
                implementation(project(":keel-testing-internal"))
            }
        }
    }
}
