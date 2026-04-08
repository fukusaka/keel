plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    macosArm64 {
        compilations["main"].cinterops {
            create("nwconnection") {
                defFile("src/nativeInterop/cinterop/nwconnection.def")
            }
        }
    }
    macosX64 {
        compilations["main"].cinterops {
            create("nwconnection") {
                defFile("src/nativeInterop/cinterop/nwconnection.def")
            }
        }
    }

    // Creates macosMain intermediate source set shared by macosArm64 and macosX64
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":keel-core"))
            }
        }
        val macosMain by getting {
            dependencies {
                implementation(project(":keel-tls"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val macosTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":keel-tls"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
