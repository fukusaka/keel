plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // JVM target
    jvm()

    // JS target (Node.js)
    js(IR) {
        nodejs()
    }

    // Native targets
    linuxX64()
    linuxArm64()
    macosArm64()
    macosX64()

    // Intermediate source set shared by all native targets
    applyDefaultHierarchyTemplate()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":logging"))
                api(project(":io-core"))
                api(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        // nativeMain is created automatically by applyDefaultHierarchyTemplate()
        // and is the parent of linuxX64Main / macosArm64Main
    }
}
