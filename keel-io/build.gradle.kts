import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
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
    macosArm64()
    macosX64()

    // Intermediate source set shared by all native targets
    applyDefaultHierarchyTemplate()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // Release-mode optimisation for the Native test binary. The default Native
    // test build is debug-compiled, which adds ~10–20× overhead and distorts
    // the thread-safety A/B benchmark comparison. Enabling `optimized = true`
    // on the existing DEBUG test binary applies `-opt` without changing the
    // test taxonomy: the same `<target>Test` task runs, `@Test` discovery
    // works, `@Ignore` is honoured. Pairing it with `debuggable = false` drops
    // the `-g` debug-symbol flag — the Kotlin/Native compiler rejects
    // `-opt` + `-g` together ("Unsupported combination of flags"), and the
    // test taxonomy doesn't need debug symbols (kotlin-test failures still
    // include test name + assertion details from the runtime, just not native
    // stack frames). Functional regression tests assert behaviour, not timing
    // or stack frames, so they are unaffected.
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries {
            getTest(NativeBuildType.DEBUG).apply {
                optimized = true
                debuggable = false
            }
        }
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
