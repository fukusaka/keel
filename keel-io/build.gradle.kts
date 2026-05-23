import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

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
    macosArm64 {
        // Provisional: opt-in release-mode test binary so the multi-seg
        // IoBuf PoC microbench (`buf.poc.PocMultiSegNativeBenchmark` and
        // `PocNativeOverheadDiagnostic`) can be measured under
        // production-realistic Kotlin/Native AOT optimisation rather
        // than the default debug-test binary. Removed once the PoC
        // decision lands.
        binaries.test("release", listOf(NativeBuildType.RELEASE))
    }
    macosX64()

    // Intermediate source set shared by all native targets
    applyDefaultHierarchyTemplate()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain {
            dependencies {
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
    }
}
