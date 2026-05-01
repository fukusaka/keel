plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val hostOs: String = System.getProperty("os.name").lowercase()
val isMacHost: Boolean = hostOs.contains("mac")
val isLinuxHost: Boolean = hostOs.contains("linux")

kotlin {
    applyDefaultHierarchyTemplate()

    jvm()
    js(IR) {
        nodejs()
        binaries.executable()
    }
    // Native benchmark targets are host-gated: their transitive dependencies
    // (keel-server-ktor → keel-engine-kqueue / keel-engine-epoll) require
    // host-specific cinterop toolchains. Declaring a Linux target on a macOS
    // host (or vice versa) causes Gradle variant resolution to fail when
    // Dokka / metadata compile pulls in the classpath (no matching variant
    // for the current host).
    if (isMacHost) {
        macosArm64 {
            binaries {
                executable {
                    entryPoint = "io.github.fukusaka.keel.benchmark.main"
                }
            }
        }
    }
    if (isLinuxHost) {
        linuxX64 {
            binaries {
                executable {
                    entryPoint = "io.github.fukusaka.keel.benchmark.main"
                }
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":keel-core"))
                implementation(project(":keel-tls"))
                implementation(project(":keel-server"))
                // PipelineHttpRoutes (in commonMain) uses keel-codec-http and
                // keel-codec-websocket symbols so these dependencies must be
                // commonMain-wide, not per-target.
                implementation(project(":keel-codec-http"))
                implementation(project(":keel-codec-websocket"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        // Ktor Server dependencies — shared by JVM and Native, but not JS.
        val commonForKtorServerMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(project(":keel-server-ktor"))
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.websockets)
            }
        }

        jvmMain {
            dependsOn(commonForKtorServerMain)
            dependencies {
                implementation(project(":keel-engine-nio"))
                implementation(project(":keel-engine-netty"))
                implementation(libs.ktor.server.netty)
                // ktor-server-compression: JVM-only artefact (no Native publication).
                // Used by BenchmarkCompression.jvm.kt's actual install hook for the
                // `compression` bench scenario; Native adapters get a no-op actual.
                implementation(libs.ktor.server.compression)
                implementation(libs.spring.boot.starter.webflux)
                implementation(libs.vertx.web)
            }
        }
        nativeMain {
            dependsOn(commonForKtorServerMain)
        }
        jsMain {
            dependencies {
                implementation(project(":keel-engine-nodejs"))
            }
        }

        // macOS: keel-kqueue + keel-nwconnection engines for pipeline benchmark.
        // Only wired when the macOS targets were declared above (isMacHost).
        if (isMacHost) {
            val macosMain by getting {
                dependencies {
                    implementation(project(":keel-engine-kqueue"))
                    implementation(project(":keel-engine-nwconnection"))
                }
            }
        }

        // Linux: keel-epoll + keel-io-uring engines for pipeline benchmark.
        // Only wired when the Linux targets were declared above (isLinuxHost).
        if (isLinuxHost) {
            val linuxMain by getting {
                dependencies {
                    implementation(project(":keel-engine-epoll"))
                    implementation(project(":keel-engine-io-uring"))
                }
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

    // Gate TLS wiring on the same host-conditional target declarations above
    // so macosMain / linuxMain are only configured when the corresponding
    // native target exists.
    if (isMacHost) {
        val macosMain = kotlin.sourceSets.getByName("macosMain")
        macosMain.kotlin.srcDir("src/macosTls-$nativeBackend/kotlin")
        macosMain.dependencies {
            implementation(project(nativeTlsProject))
        }
    }
    if (isLinuxHost) {
        val linuxMain = kotlin.sourceSets.getByName("linuxMain")
        linuxMain.kotlin.srcDir("src/linuxTls-$nativeBackend/kotlin")
        linuxMain.dependencies {
            implementation(project(nativeTlsProject))
        }
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
