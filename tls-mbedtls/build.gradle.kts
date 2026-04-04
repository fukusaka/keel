plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    macosArm64 {
        compilations["main"].cinterops {
            create("mbedtls") {
                defFile("src/nativeInterop/cinterop/mbedtls.def")
            }
        }
    }
    macosX64 {
        compilations["main"].cinterops {
            create("mbedtls") {
                defFile("src/nativeInterop/cinterop/mbedtls.def")
            }
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":tls"))
            }
        }
        val macosTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":logging"))
                implementation(project(":engine-kqueue"))
                implementation(project(":codec-http"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
