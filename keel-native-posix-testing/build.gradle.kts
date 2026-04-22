plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// keel-native-posix-testing holds test-only infrastructure previously
// embedded inside keel-native-posix/nativeMain:
// - FakeNativeSocket / FakeNativeSocketOps — scripted in-memory fakes
//   for engine seam tests
// - PosixRawClient — test helper TCP client for integration tests
// - InternalTestApi — opt-in annotation for the above
//
// Extracting these into a separate module keeps the production
// keel-native-posix artifact free of test scaffolding. This module is
// NOT published to Maven (no maven-publish plugin) and NOT included in
// the Dokka publication (see root build.gradle.kts `skipDokka`).

kotlin {
    linuxX64 {
        compilations["main"].cinterops {
            create("posix_testing") {
                defFile("src/nativeInterop/cinterop/posix_testing.def")
            }
        }
    }
    linuxArm64 {
        compilations["main"].cinterops {
            create("posix_testing") {
                defFile("src/nativeInterop/cinterop/posix_testing.def")
            }
        }
    }
    macosArm64 {
        compilations["main"].cinterops {
            create("posix_testing") {
                defFile("src/nativeInterop/cinterop/posix_testing.def")
            }
        }
    }
    macosX64 {
        compilations["main"].cinterops {
            create("posix_testing") {
                defFile("src/nativeInterop/cinterop/posix_testing.def")
            }
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                // api: subclasses / users can reference NativeSocket / NativeSocketOps
                // interfaces + sealed result types (ReadResult / WriteResult / AcceptResult
                // etc.) directly without adding keel-native-posix as a separate dep.
                api(project(":keel-native-posix"))
                implementation(project(":keel-core"))
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
