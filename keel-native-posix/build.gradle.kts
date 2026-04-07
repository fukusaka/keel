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
            }
        }
    }
}
