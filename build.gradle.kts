plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.detekt)
}

// Shorten package names in Dokka navigation sidebar.
// customAssets only injects into root index.html; subpages need the script
// in scripts/ directory alongside navigation-loader.js.
dokka {
    pluginsConfiguration.html {
        customAssets.from("dokka/scripts/shorten-packages.js")
    }
}

// Copy shorten-packages.js to Dokka scripts/ directory so all pages load it.
tasks.named("dokkaGeneratePublicationHtml") {
    doLast {
        val scriptsDir = layout.buildDirectory.dir("dokka/html/scripts").get().asFile
        val source = project.file("dokka/scripts/shorten-packages.js")
        source.copyTo(scriptsDir.resolve("shorten-packages.js"), overwrite = true)
        // Inject script tag into all HTML files that reference navigation-loader.js
        val htmlDir = layout.buildDirectory.dir("dokka/html").get().asFile
        htmlDir.walkTopDown().filter { it.extension == "html" }.forEach { file ->
            val content = file.readText()
            if ("shorten-packages.js" !in content && "navigation-loader.js" in content) {
                val replacement = content.replace(
                    "</head>",
                    """<script type="text/javascript" src="${"scripts/shorten-packages.js".let { script ->
                        // Compute relative path based on depth
                        val depth = file.relativeTo(htmlDir).path.count { it == '/' }
                        "../".repeat(depth) + script
                    }}" defer></script></head>""",
                )
                file.writeText(replacement)
            }
        }
    }
}

// Modules requiring platform-specific cinterop headers unavailable on other hosts.
// Dokka triggers cinterop tasks which fail when the required headers are missing.
val hostOs = System.getProperty("os.name").lowercase()
val hostArch = System.getProperty("os.arch").lowercase()
val isLinux = hostOs.contains("linux")
val isMacos = hostOs.contains("mac")
val isX64 = hostArch == "amd64" || hostArch == "x86_64"
val isArm64 = hostArch == "aarch64" || hostArch == "arm64"
val linuxOnlyModules = setOf("engine-io-uring", "engine-epoll")
val macosOnlyModules = setOf("engine-kqueue", "engine-nwconnection")

// Cross-architecture cinterop targets that lack host headers.
// e.g., linuxArm64 cinterop on x86_64 host fails (missing gnu/stubs-32.h),
//       macosX64 cinterop on arm64 host may fail similarly.
// Suppress these source sets in Dokka — API is identical across architectures.
val suppressedDokkaSourceSets: Set<String> = buildSet {
    if (isLinux && isX64) {
        add("linuxArm64Main")
    } else if (isLinux && isArm64) {
        add("linuxX64Main")
    }
    if (isMacos && isArm64) {
        add("macosX64Main")
    } else if (isMacos && isX64) {
        add("macosArm64Main")
    }
}

dependencies {
    dokka(project(":logging"))
    dokka(project(":io-core"))
    dokka(project(":core"))
    if (isLinux) dokka(project(":engine-epoll"))
    if (isMacos) dokka(project(":engine-kqueue"))
    dokka(project(":engine-nio"))
    dokka(project(":engine-netty"))
    dokka(project(":engine-nodejs"))
    if (isMacos) dokka(project(":engine-nwconnection"))
    if (isLinux) dokka(project(":engine-io-uring"))
    dokka(project(":codec-http"))
    dokka(project(":codec-websocket"))
    dokka(project(":ktor-engine"))
}

// Suppress per-module Dokka URL output; show only the aggregated root URL.
subprojects {
    val skipDokka = (name in linuxOnlyModules && !isLinux) ||
        (name in macosOnlyModules && !isMacos)
    if (!skipDokka) {
        apply(plugin = "org.jetbrains.dokka")
        afterEvaluate {
            tasks.matching { it.name == "logLinkDokkaGeneratePublicationHtml" }.configureEach {
                enabled = false
            }
        }
        extensions.findByType<org.jetbrains.dokka.gradle.DokkaExtension>()?.apply {
            dokkaSourceSets.configureEach {
                // Document all visibility levels for complete API reference.
                documentedVisibilities.set(setOf(
                    org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier.Public,
                    org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier.Internal,
                    org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier.Protected,
                    org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier.Private,
                ))

                // Link each declaration to its source on GitHub.
                sourceLink {
                    localDirectory.set(project.projectDir.resolve("src"))
                    remoteUrl("https://github.com/fukusaka/keel/blob/main/${project.name}/src")
                    remoteLineSuffix.set("#L")
                }

                // Module and package documentation.
                val moduleDoc = project.file("module.md")
                if (moduleDoc.exists()) {
                    includes.from(moduleDoc)
                }

                // Suppress cross-architecture source sets to avoid cinterop failures.
                if (name in suppressedDokkaSourceSets) {
                    suppress.set(true)
                }
            }
        }
        // Disable cinterop tasks for cross-platform/cross-architecture targets.
        // e.g., cinteropMbedtlsLinuxArm64 on macOS fails due to missing Linux headers.
        afterEvaluate {
            if (isMacos) {
                tasks.matching { it.name.startsWith("cinterop") && it.name.contains("Linux", ignoreCase = true) }.configureEach {
                    enabled = false
                }
            } else if (isLinux) {
                tasks.matching { it.name.startsWith("cinterop") && it.name.contains("Macos", ignoreCase = true) }.configureEach {
                    enabled = false
                }
            }
            if (isX64) {
                tasks.matching { it.name.startsWith("cinterop") && it.name.contains("Arm64", ignoreCase = true) }.configureEach {
                    enabled = false
                }
            } else if (isArm64) {
                tasks.matching { it.name.startsWith("cinterop") && it.name.contains("X64", ignoreCase = true) }.configureEach {
                    enabled = false
                }
            }
        }
    }
    group = "io.github.fukusaka.keel"
    version = "0.2.0-SNAPSHOT"

    // Apply detekt to production modules
    if (name !in setOf("benchmark", "sample", "detekt-rules")) {
        apply(plugin = "io.gitlab.arturbosch.detekt")
        configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            config.setFrom(rootProject.file("detekt.yml"))
            buildUponDefaultConfig = true
        }
        // engine-netty: lint-only (no type resolution, no custom rules).
        // detekt type resolution crashes on Netty's external API with NPE
        // in IgnoredReturnValue → DescriptorUtilKt.findPackage (detekt 1.23.8).
        // Standard rules still work via the `detekt` task (lint-only).
        // Custom rules (IoBufLeak etc.) are excluded because they
        // produce false positives without type resolution.
        // Type resolution tasks (detektJvmMain etc.) must NOT be run for
        // this module — use `detekt` task only.
        if (name != "engine-netty") {
            dependencies {
                "detektPlugins"(project(":detekt-rules"))
                "detektPlugins"(rootProject.libs.detekt.formatting)
            }
        } else {
            // Disable type resolution tasks to prevent NPE in CI.
            // Only the lint-only `detekt` task is safe to run.
            afterEvaluate {
                tasks.matching { it.name.startsWith("detekt") && it.name != "detekt" }.configureEach {
                    enabled = false
                }
            }
        }
    }
}
