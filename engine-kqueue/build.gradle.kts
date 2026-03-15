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
                implementation(project(":core"))
            }
        }
    }
}
