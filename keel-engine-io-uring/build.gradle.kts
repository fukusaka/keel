plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    linuxX64 {
        compilations["main"].cinterops {
            create("io_uring") {
                defFile("src/nativeInterop/cinterop/io_uring.def")
            }
        }
    }
    linuxArm64 {
        compilations["main"].cinterops {
            create("io_uring") {
                defFile("src/nativeInterop/cinterop/io_uring.def")
            }
        }
    }

    // Creates linuxMain intermediate source set shared by linuxX64 and linuxArm64
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":keel-core"))
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
            }
        }
    }
}
