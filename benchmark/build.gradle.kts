plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    applyDefaultHierarchyTemplate()

    jvm()
    macosArm64 {
        binaries {
            executable {
                entryPoint = "io.github.fukusaka.keel.benchmark.main"
            }
        }
    }
    linuxX64 {
        binaries {
            executable {
                entryPoint = "io.github.fukusaka.keel.benchmark.main"
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
                implementation(project(":engine-nio"))
                implementation(project(":engine-netty"))
                implementation(project(":codec-http"))
                implementation(libs.ktor.server.netty)
                implementation(libs.spring.boot.starter.webflux)
                implementation(libs.vertx.web)
            }
        }
        nativeMain {
        }

        // macOS: keel-kqueue + keel-nwconnection engines + HTTP codec for pipeline benchmark
        val macosMain by getting {
            dependencies {
                implementation(project(":engine-kqueue"))
                implementation(project(":engine-nwconnection"))
                implementation(project(":codec-http"))
            }
        }

        // Linux: keel-epoll + keel-io-uring engines + HTTP codec for pipeline benchmark
        val linuxMain by getting {
            dependencies {
                implementation(project(":engine-epoll"))
                implementation(project(":engine-io-uring"))
                implementation(project(":codec-http"))
            }
        }
    }
}

tasks.register<JavaExec>("run") {
    description = "Run benchmark server (--engine=keel|keel-netty|cio|ktor-netty|spring|vertx)"
    mainClass.set("io.github.fukusaka.keel.benchmark.JvmMainKt")
    classpath = kotlin.jvm().compilations["main"].runtimeDependencyFiles +
        kotlin.jvm().compilations["main"].output.allOutputs
    standardInput = System.`in`
}

// Write classpath file for running JVM benchmark without Gradle process tree.
// Usage: java -cp @benchmark/build/benchmark-classpath.txt io.github.fukusaka.keel.benchmark.JvmMainKt
tasks.register("writeClasspath") {
    val jvmCompilation = kotlin.jvm().compilations["main"]
    dependsOn(jvmCompilation.compileTaskProvider)
    val outputFile = layout.buildDirectory.file("benchmark-classpath.txt")
    outputs.file(outputFile)
    doLast {
        val cp = (jvmCompilation.output.allOutputs + jvmCompilation.runtimeDependencyFiles)
            .joinToString(File.pathSeparator)
        outputFile.get().asFile.writeText(cp)
    }
}

tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "io.github.fukusaka.keel.benchmark.JvmMainKt"
    }
    val jvmCompilation = kotlin.jvm().compilations["main"]
    from(jvmCompilation.output.allOutputs)
    dependsOn(jvmCompilation.compileTaskProvider)
    from({
        jvmCompilation.runtimeDependencyFiles
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
}
