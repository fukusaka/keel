plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // JVM target
    jvm()

    // Native targets
    linuxX64()
    macosArm64()

    // Intermediate source set shared by all native targets
    applyDefaultHierarchyTemplate()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlinx.io.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        // nativeMain is created automatically by applyDefaultHierarchyTemplate()
        // and is the parent of linuxX64Main / macosArm64Main
    }
}
