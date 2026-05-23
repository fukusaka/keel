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
        // PoC scope: source set for tests that run on JVM + Native
        // but NOT on JS. The multi-seg IoBuf PoC's `expect`/`actual`
        // segment-access shim has JS stubs that throw — those exist
        // only to satisfy the contract, not as a real impl — so tests
        // that exercise the real read/write paths must skip JS.
        // Removed alongside `buf.poc.*` once the candidate decision
        // lands.
        val jvmAndNativeTest by creating {
            dependsOn(commonTest.get())
        }
        jvmTest { dependsOn(jvmAndNativeTest) }
        nativeTest { dependsOn(jvmAndNativeTest) }
    }
}
