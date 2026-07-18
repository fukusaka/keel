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
                implementation(project(":keel-server-http"))
                // PipelineHttpRoutes (in commonMain) uses keel-codec-http and
                // keel-codec-websocket symbols so these dependencies must be
                // commonMain-wide, not per-target.
                implementation(project(":keel-codec-http"))
                implementation(project(":keel-codec-websocket"))
                // server-http-* benchmarks wire a `/ws-deflate` endpoint via
                // the `webSockets(codec) { }` DSL (WS-3 permessage-deflate).
                // keel-server-websocket is `api(keel-server-http)`, so this
                // does not conflict with the existing keel-server-http dep.
                implementation(project(":keel-server-websocket"))
                // pipeline-http engines wire `keel-compression-zlib`
                // (gzip / deflate backend) when `--compression=true`.
                implementation(project(":keel-compression-zlib"))
                implementation(libs.kotlinx.coroutines.core)
                // ServerHttpBenchRoutes' in-memory static-asset bench (the
                // `--static-file-bytes` micro-bench) returns a kotlinx.io
                // RawSource from Asset.open, so the symbol must be a direct
                // dependency (transitive resolution is JS-target unreliable).
                implementation(libs.kotlinx.io.core)
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
                implementation(project(":keel-server-ktor-cio"))
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
                // Client benchmark harness (--role=client): reference client engines
                // + HdrHistogram for coordinated-omission-corrected latency percentiles.
                // JVM-only. CIO is Ktor's pure-Kotlin engine (does not reuse keep-alive,
                // KTOR-6503); OkHttp / Apache5 / Java delegate to mature libraries that
                // pool connections and speak HTTP/2 — the pooling-capable A/B references.
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.ktor.client.apache5)
                implementation(libs.ktor.client.java)
                implementation(libs.hdrhistogram)
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

// Security pin for a transitive Jackson brought in by the JVM reference
// benchmark servers: io.vertx:vertx-core 5.0.8 declares jackson-databind /
// jackson-core 2.15.3, which carries the Dependabot advisories (databind
// CVE < 2.18.8, core CVE <= 2.18.5). spring-boot 3.5.12's jackson-bom already
// upgrades the resolved version to 2.19.4; this constraint makes that
// security floor explicit so the advisory is cleared and cannot silently
// regress if the bom alignment changes. Scoped to jvmMainImplementation
// because Jackson only reaches the -Pbenchmark reference servers (spring /
// vertx) — it is NOT a dependency of any published keel library module.
dependencies {
    constraints {
        "jvmMainImplementation"("com.fasterxml.jackson.core:jackson-databind:2.19.4") {
            because("CVE-fixed floor for the transitive Jackson pulled by vertx / spring benchmark servers")
        }
        "jvmMainImplementation"("com.fasterxml.jackson.core:jackson-core:2.19.4") {
            because("CVE-fixed floor for the transitive Jackson pulled by vertx / spring benchmark servers")
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
//
// Output is anchored to two stable placeholders so the file is portable across
// hosts that share a repo layout and a Gradle user home:
//
//   ${REPO_ROOT}/...        — paths inside the rootProject's projectDir.
//   ${GRADLE_USER_HOME}/... — paths inside the Gradle user home (the dep cache).
//   /absolute/path          — anything else (rare; e.g. system Java extension jars).
//
// Bench scripts (see benchmark/bench-jvm-cp.sh) substitute these placeholders
// against the running host's $PWD and $GRADLE_USER_HOME / ~/.gradle and verify
// the resolved entries exist on disk before launching the JVM — so an operator
// who copies the file to a host whose layout differs gets a clear
// `JVM_CP_INVALID` failure instead of a confusing `READY_TIMEOUT_7`.
//
// Usage: bash benchmark/bench-jvm-cp.sh resolve   # echoes resolved CP
//        bash benchmark/bench-jvm-cp.sh check     # exits 0 / nonzero
tasks.register("writeClasspath") {
    val jvmCompilation = kotlin.jvm().compilations["main"]
    dependsOn(jvmCompilation.compileTaskProvider)
    // Without these, Gradle has no real inputs to compare and treats the
    // task as up-to-date forever once the output file exists — including
    // across a `-Ptls` toggle. `-Ptls` conditionally adds the
    // `keel-tls-jsse` dependency to jvmMain below, which changes
    // runtimeDependencyFiles; without inputs.property("tls", ...) that
    // change alone doesn't invalidate the cache either, so a classpath
    // written without `-Ptls` silently keeps missing the JSSE jar on a
    // later `-Ptls`-enabled run, breaking JVM HTTPS benchmarks.
    inputs.files(jvmCompilation.output.allOutputs)
    inputs.files(jvmCompilation.runtimeDependencyFiles)
    inputs.property("tls", providers.gradleProperty("tls").isPresent)
    inputs.property("tlsBackend", providers.gradleProperty("tls-backend").getOrElse(""))
    val outputFile = layout.buildDirectory.file("benchmark-classpath.txt")
    outputs.file(outputFile)
    val repoRoot = rootProject.projectDir.absolutePath
    val gradleUserHome = gradle.gradleUserHomeDir.absolutePath
    doLast {
        val entries = jvmCompilation.output.allOutputs + jvmCompilation.runtimeDependencyFiles
        val cp = entries.joinToString(File.pathSeparator) { f ->
            val p = f.absolutePath
            when {
                p.startsWith("$repoRoot/") || p == repoRoot ->
                    "\${REPO_ROOT}" + p.removePrefix(repoRoot)
                p.startsWith("$gradleUserHome/") || p == gradleUserHome ->
                    "\${GRADLE_USER_HOME}" + p.removePrefix(gradleUserHome)
                else -> p
            }
        }
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
