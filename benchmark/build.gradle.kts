plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()

    sourceSets {
        jvmMain {
            dependencies {
                implementation(project(":core"))
                implementation(project(":ktor-engine"))
                implementation(project(":engine-netty"))
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.netty)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.spring.boot.starter.webflux)
                implementation(libs.vertx.web)
            }
        }
    }
}

tasks.register<JavaExec>("run") {
    description = "Run benchmark server (--engine=keel|keel-netty|cio|ktor-netty|spring|vertx)"
    mainClass.set("io.github.keel.benchmark.BenchmarkAppKt")
    classpath = kotlin.jvm().compilations["main"].runtimeDependencyFiles +
        kotlin.jvm().compilations["main"].output.allOutputs
    standardInput = System.`in`
}
