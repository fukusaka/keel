plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    linuxX64 {
        compilations["main"].cinterops {
            create("posix_socket") {
                defFile("src/nativeInterop/cinterop/posix_socket.def")
            }
            create("posix_inet") {
                defFile("src/linuxInterop/cinterop/posix_inet.def")
            }
        }
    }
    linuxArm64 {
        compilations["main"].cinterops {
            create("posix_socket") {
                defFile("src/nativeInterop/cinterop/posix_socket.def")
            }
            create("posix_inet") {
                defFile("src/linuxInterop/cinterop/posix_inet.def")
            }
        }
    }
    macosArm64 {
        compilations["main"].cinterops {
            create("posix_socket") {
                defFile("src/nativeInterop/cinterop/posix_socket.def")
            }
        }
    }
    macosX64 {
        compilations["main"].cinterops {
            create("posix_socket") {
                defFile("src/nativeInterop/cinterop/posix_socket.def")
            }
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":keel-core"))
                // api, not implementation: kept for consumers that were resolving
                // coroutines through this module. Nothing here uses it — the source
                // set names no kotlinx.coroutines type — so it is a compatibility
                // entry, not a requirement of this module.
                api(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
