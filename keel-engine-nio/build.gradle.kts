plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":keel-core"))
            }
        }
        jvmMain {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
                // Test-only: exercises the NIO engine with the public Netty-backed
                // allocator (nettyByteBufAllocator), the benchmark comparison baseline.
                implementation(project(":keel-engine-netty"))
            }
        }
    }
}
