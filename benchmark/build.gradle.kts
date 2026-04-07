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
                implementation(project(":keel-core"))
                implementation(project(":keel-tls"))
                implementation(project(":keel-ktor-engine"))
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        jvmMain {
            dependencies {
                implementation(project(":keel-engine-nio"))
                implementation(project(":keel-engine-netty"))
                implementation(project(":keel-codec-http"))
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
                implementation(project(":keel-engine-kqueue"))
                implementation(project(":keel-engine-nwconnection"))
                implementation(project(":keel-codec-http"))
            }
        }

        // Linux: keel-epoll + keel-io-uring engines + HTTP codec for pipeline benchmark
        val linuxMain by getting {
            dependencies {
                implementation(project(":keel-engine-epoll"))
                implementation(project(":keel-engine-io-uring"))
                implementation(project(":keel-codec-http"))
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

// TLS benchmark dependencies — only available with -Ptls.
// Enables --tls=<backend> CLI flag for HTTPS benchmarking.
//
// Native: only ONE TLS backend per binary (OpenSSL and AWS-LC share
// libssl/libcrypto symbol names — linking both causes symbol conflicts).
// Use -Ptls-backend=openssl|awslc|mbedtls to select (default: openssl).
//
// JVM: always uses JSSE (no conflict).
if (providers.gradleProperty("tls").isPresent) {
    val nativeBackend = providers.gradleProperty("tls-backend").getOrElse("openssl")

    kotlin.sourceSets.getByName("jvmMain") {
        kotlin.srcDir("src/jvmTls/kotlin")
        dependencies {
            implementation(project(":keel-tls-jsse"))
        }
    }

    val nativeTlsProject = when (nativeBackend) {
        "openssl" -> ":keel-tls-openssl"
        "awslc" -> ":keel-tls-awslc"
        "mbedtls" -> ":keel-tls-mbedtls"
        else -> error("Unknown TLS backend: $nativeBackend (available: openssl, awslc, mbedtls)")
    }

    val macosMain = kotlin.sourceSets.getByName("macosMain")
    macosMain.kotlin.srcDir("src/macosTls-$nativeBackend/kotlin")
    macosMain.dependencies {
        implementation(project(nativeTlsProject))
    }
    val linuxMain = kotlin.sourceSets.getByName("linuxMain")
    linuxMain.kotlin.srcDir("src/linuxTls-$nativeBackend/kotlin")
    linuxMain.dependencies {
        implementation(project(nativeTlsProject))
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
