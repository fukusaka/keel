plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // OpenTelemetry Java SDK is JVM-only. Native and JS targets are excluded
    // by design — keel-io's BufferAllocator observer hook interfaces are
    // platform-neutral, but OT consumers running on Native / JS will need
    // their own adapter modules (or use the existing in-process
    // PoolMissProfile / TrackingAllocator / LeakDetectingAllocator paths).
    jvm()

    sourceSets {
        jvmMain {
            dependencies {
                implementation(project(":keel-io"))
                implementation(libs.opentelemetry.api)
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.opentelemetry.sdk)
                implementation(libs.opentelemetry.sdk.testing)
            }
        }
    }
}
