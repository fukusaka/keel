plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    applyDefaultHierarchyTemplate()

    jvm()
    macosArm64 {
        binaries {
            executable {
                entryPoint = "io.github.keel.benchmark.main"
            }
        }
    }
    linuxX64 {
        binaries {
            executable {
                entryPoint = "io.github.keel.benchmark.main"
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":core"))
                implementation(project(":ktor-engine"))
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        jvmMain {
            dependencies {
                implementation(project(":engine-netty"))
                implementation(libs.ktor.server.netty)
                implementation(libs.spring.boot.starter.webflux)
                implementation(libs.vertx.web)
            }
        }
        nativeMain {
        }

        // macOS: keel-kqueue + keel-nwconnection engines
        val macosMain by getting {
            dependencies {
                implementation(project(":engine-kqueue"))
                implementation(project(":engine-nwconnection"))
            }
        }

        // Linux: keel-epoll engine
        val linuxMain by getting {
            dependencies {
                implementation(project(":engine-epoll"))
            }
        }
    }
}

tasks.register<JavaExec>("run") {
    description = "Run benchmark server (--engine=keel|keel-netty|cio|ktor-netty|spring|vertx)"
    mainClass.set("io.github.keel.benchmark.JvmMainKt")
    classpath = kotlin.jvm().compilations["main"].runtimeDependencyFiles +
        kotlin.jvm().compilations["main"].output.allOutputs
    standardInput = System.`in`
}
