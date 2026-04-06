plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    macosArm64 {
        compilations["main"].cinterops {
            create("awslc") {
                defFile("src/nativeInterop/cinterop/awslc.def")
            }
        }
    }
    linuxX64 {
        compilations["main"].cinterops {
            create("awslc") {
                defFile("src/nativeInterop/cinterop/awslc.def")
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
        nativeTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":logging"))
                implementation(project(":codec-http"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val macosTest by getting {
            dependencies {
                implementation(project(":engine-kqueue"))
            }
        }
        val linuxTest by getting {
            dependencies {
                implementation(project(":engine-epoll"))
            }
        }
    }
}
