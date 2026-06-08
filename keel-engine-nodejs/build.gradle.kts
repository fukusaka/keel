plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    js(IR) {
        nodejs {
            testTask {
                useMocha {
                    // Default Mocha per-test timeout is 2 s. The real-socket idle-timeout
                    // integration tests measure real wall-clock intervals (a connection is
                    // force-closed only after its idle timeout elapses), which exceeds 2 s.
                    timeout = "30s"
                }
            }
        }
    }

    sourceSets {
        jsMain {
            dependencies {
                implementation(project(":keel-core"))
                implementation(project(":keel-tls"))
                implementation(project(":keel-server"))
            }
        }
        jsTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
