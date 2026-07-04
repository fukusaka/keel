import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult

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
    // Resolved at configuration time — capturing project.file(...) inside the
    // doLast action would serialize a Project reference and break the
    // configuration cache.
    val scriptSource = layout.projectDirectory.file("dokka/scripts/shorten-packages.js")
    val htmlOutput = layout.buildDirectory.dir("dokka/html")
    doLast {
        val scriptsDir = htmlOutput.get().dir("scripts").asFile
        val source = scriptSource.asFile
        source.copyTo(scriptsDir.resolve("shorten-packages.js"), overwrite = true)
        // Inject script tag into all HTML files that reference navigation-loader.js
        val htmlDir = htmlOutput.get().asFile
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
val linuxOnlyModules = setOf("keel-engine-io-uring", "keel-engine-epoll")
val macosOnlyModules = setOf("keel-engine-kqueue", "keel-engine-nwconnection")

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

val isTls = providers.gradleProperty("tls").isPresent

dependencies {
    dokka(project(":keel-io"))
    dokka(project(":keel-core"))
    dokka(project(":keel-native-posix"))
    if (isLinux) dokka(project(":keel-engine-epoll"))
    if (isMacos) dokka(project(":keel-engine-kqueue"))
    dokka(project(":keel-engine-nio"))
    dokka(project(":keel-engine-netty"))
    dokka(project(":keel-engine-nodejs"))
    if (isMacos) dokka(project(":keel-engine-nwconnection"))
    if (isLinux) dokka(project(":keel-engine-io-uring"))
    dokka(project(":keel-tls"))
    dokka(project(":keel-tls-jsse"))
    if (isTls) {
        if (isLinux || isMacos) dokka(project(":keel-tls-openssl"))
        if (isLinux || isMacos) dokka(project(":keel-tls-mbedtls"))
        if (isLinux || isMacos) dokka(project(":keel-tls-awslc"))
    }
    dokka(project(":keel-codec-http"))
    dokka(project(":keel-codec-websocket"))
    dokka(project(":keel-server"))
    dokka(project(":keel-server-http"))
    dokka(project(":keel-server-websocket"))
    dokka(project(":keel-server-ktor-base"))
    dokka(project(":keel-server-ktor"))
    dokka(project(":keel-server-ktor-cio"))
    dokka(project(":keel-testing-engine"))
    dokka(project(":keel-testing-server-http"))
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
        // Disable all tasks (cinterop + compile + link + klibrary + binaries)
        // for cross-platform / cross-architecture targets that cannot be built
        // on the current host. e.g. cinteropMbedtlsLinuxArm64 on macOS fails
        // due to missing Linux headers; compileKotlinMacosX64 on macosArm64
        // host fails because its cinterop klib was disabled above but Kotlin
        // still tries to run the downstream compile. Disabling the whole
        // per-target task family (`(cinterop|compile|link|cleanNative)` +
        // `<target>Binaries` / `<target>MainKlibrary` / `<target>ProcessResources`
        // etc. — everything containing the target token) lets `./gradlew
        // assemble` succeed on a mismatched host.
        afterEvaluate {
            fun disableTasksForTarget(token: String) {
                tasks.matching { it.name.contains(token, ignoreCase = true) }.configureEach {
                    enabled = false
                }
            }
            if (isMacos) {
                disableTasksForTarget("LinuxX64")
                disableTasksForTarget("LinuxArm64")
            } else if (isLinux) {
                disableTasksForTarget("MacosX64")
                disableTasksForTarget("MacosArm64")
            }
            // Same-OS cross-architecture: disable the off-arch target compile
            // since its cinterop cannot run on this host architecture.
            if (isArm64) {
                disableTasksForTarget("LinuxX64")
                disableTasksForTarget("MacosX64")
            } else if (isX64) {
                disableTasksForTarget("LinuxArm64")
                disableTasksForTarget("MacosArm64")
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
            // Per-module baseline grandfathering pre-existing intermediate-source-set
            // (nativeMain / appleMain / macosMain / linuxMain) findings that the CI
            // gate did not previously run. detekt treats a missing baseline file as
            // "no baseline" (analyse everything), so modules without one are unaffected.
            // The gate enforces zero NEW findings; the grandfathered set is cleared
            // incrementally per module and the baseline file is removed once empty.
            baseline = file("detekt-baseline.xml")
        }
        // engine-netty: lint-only (no type resolution, no custom rules).
        // detekt type resolution crashes on Netty's external API with NPE
        // in IgnoredReturnValue → DescriptorUtilKt.findPackage (detekt 1.23.8).
        // Standard rules still work via the `detekt` task (lint-only).
        // Custom rules (IoBufLeak etc.) are excluded because they
        // produce false positives without type resolution.
        // Type resolution tasks (detektJvmMain etc.) must NOT be run for
        // this module — use `detekt` task only.
        if (name != "keel-engine-netty") {
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

        // Slow-test budget guard (test classification forcing function).
        //
        // Measurement code (`*Benchmark` / `*Audit`) has no functional
        // assertion and is `@Ignore`d so it does not run in the gate / CI.
        // The naming convention
        // alone cannot catch a measurement that slips in *without* `@Ignore`
        // (forgotten annotation, or measurement code misnamed `*Test`): it
        // would silently run and inflate the suite by tens of seconds. This
        // listener flags any executed test exceeding the budget so the drift
        // is visible in the build log — a legitimate test never approaches it
        // (the slowest real / `*Measure` tests are ~2 s; the retired
        // benchmarks were 50-111 s). Warn-only: CI-runner load can make a
        // normally-fast test spike, so this must not fail the build.
        //
        // `SlowTestWarningListener` is a top-level class capturing no script
        // / Project reference (it prints to stderr, not the project logger,
        // and the budget is its own constant) so the task stays
        // configuration-cache serializable.
        tasks.withType<AbstractTestTask>().configureEach {
            addTestListener(SlowTestWarningListener())
        }
    }
}

/**
 * Warns (to stderr) when an executed test exceeds [SLOW_TEST_BUDGET_SEC].
 * Holds no reference to the build script or `Project`, so a `Test` task it
 * is attached to remains serializable for the configuration cache.
 */
class SlowTestWarningListener : TestListener {
    override fun beforeSuite(suite: TestDescriptor) {}
    override fun beforeTest(testDescriptor: TestDescriptor) {}
    override fun afterSuite(suite: TestDescriptor, result: TestResult) {}
    override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
        val elapsedSec = (result.endTime - result.startTime) / 1000.0
        if (elapsedSec > SLOW_TEST_BUDGET_SEC) {
            System.err.println(
                "SLOW TEST (>%.0fs): %s.%s took %.1fs — if this is measurement code, ".format(
                    SLOW_TEST_BUDGET_SEC, testDescriptor.className, testDescriptor.name, elapsedSec,
                ) + "name it *Benchmark/*Audit and add @Ignore so it is not run in CI.",
            )
        }
    }
    private companion object {
        private const val SLOW_TEST_BUDGET_SEC = 20.0
    }
}
