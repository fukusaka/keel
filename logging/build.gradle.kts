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

    sourceSets {
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
