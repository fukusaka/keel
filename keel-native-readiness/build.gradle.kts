plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // No cinterop of its own: everything here is Kotlin over the POSIX socket
    // seam in :keel-native-posix plus the Kotlin/Native platform library
    // (pthread, SHUT_WR, errno). That is why this module can be built on every
    // native host, unlike the engines, whose readiness primitive needs headers
    // only one host has.
    linuxX64()
    linuxArm64()
    macosArm64()
    macosX64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":keel-core"))
                // api, not implementation: the loop extends CoroutineDispatcher
                // and takes CancellableContinuation in its public signatures, so
                // the engines need these on their compile classpath.
                api(libs.kotlinx.coroutines.core)
                // api: the transport takes a NativeSocket and the servers a
                // NativeSocketOps, both from the POSIX seam.
                api(project(":keel-native-posix"))
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        nativeTest {
            dependencies {
                // FakeNativeSocket / FailingReleaseIoBuf / InjectedFault — the
                // scripted POSIX seam the transport tests drive their errno and
                // failure branches through, same as the engines' seam tests.
                implementation(project(":keel-testing-internal"))
            }
        }
    }
}
