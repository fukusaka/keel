plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    macosArm64 {
        compilations["main"].cinterops {
            create("kqueue") {
                defFile("src/nativeInterop/cinterop/kqueue.def")
            }
        }
    }
    macosX64 {
        compilations["main"].cinterops {
            create("kqueue") {
                defFile("src/nativeInterop/cinterop/kqueue.def")
            }
        }
    }

    // Creates macosMain intermediate source set shared by macosArm64 and macosX64
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":keel-core"))
                implementation(project(":keel-native-posix"))
                implementation(project(":keel-native-readiness"))
            }
        }
        val macosMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val macosTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":keel-codec-http"))
                // FakeNativeSocket / FakeNativeSocketOps / PosixRawClient /
                // InternalTestApi were extracted from keel-native-posix's
                // nativeMain into this test-only module on 2026-04-23.
                implementation(project(":keel-testing-internal"))
            }
        }
    }
}
