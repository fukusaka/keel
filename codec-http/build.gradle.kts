plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()
    linuxX64()
    linuxArm64()
    macosArm64()
    macosX64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.io.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
