import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult

// Security pin for transitive Jackson from the Dokka Gradle plugin (latest
// 2.2.0-Beta included), which declares Jackson 2.15.3 with known CVEs. Jackson
// loads in two places and each needs its own pin: the root build classpath
// (plugin JVM, this block) and the Dokka Generator worker classpath (the
// project-level dokka*Resolver~internal configurations, constrained after the
// plugins block below — a buildscript block is evaluated before the script body,
// so the two cannot share one constant). databind and core are pinned explicitly
// as the advisory-flagged artifacts; the Jackson BOM aligns the rest of the
// family (annotations / dataformat-xml / module-kotlin) to match.
// Build-tool only — never shipped in any keel artifact.
buildscript {
    val jacksonCveFloor = "2.22.1"
    dependencies {
        constraints {
            classpath("com.fasterxml.jackson.core:jackson-databind:$jacksonCveFloor") {
                because("CVE-fixed floor for Jackson pulled in by the Dokka Gradle plugin")
            }
            classpath("com.fasterxml.jackson.core:jackson-core:$jacksonCveFloor") {
                because("CVE-fixed floor for Jackson pulled in by the Dokka Gradle plugin")
            }
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.detekt)
}

// Security pins for transitive npm deps of the Kotlin/JS build / test toolchain
// (webpack bundler + mocha runner). These never ship in any keel artifact and
// run only at build time on trusted inputs, but the pins clear the Dependabot
// advisories against kotlin-js-store/yarn.lock. Forced via Yarn resolutions
// because the KGP-managed toolchain otherwise pins the vulnerable versions
// exactly (kotlinUpgradeYarnLock alone is a no-op).
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension>().apply {
        resolution("brace-expansion", "2.0.3")
        resolution("js-yaml", "4.2.0")
        resolution("serialize-javascript", "7.0.6")
        resolution("diff", "8.0.3")
    }
}

// Security pin for the Dokka Generator worker classpath: the generator runs in
// a worker JVM whose classpath comes from the project-level
// dokka*Resolver~internal configurations (root and every documented subproject),
// NOT the buildscript classpath pinned above — without this block the worker
// would still load the CVE-carrying Jackson 2.15.3 that dokka-core declares.
// Same floor and rationale as the buildscript block above. The resolver
// configurations are resolvable-only and reject declared constraints, so the
// constraints go on the declarable dokkaHtml* buckets they extend from.
// The lazy configurations.matching form is required here (and in the SwiftExport
// block below): the plugin creates these configurations after this top-level
// action runs, so the eager `dependencies { constraints { add("<name>", ...) } }`
// form would not find them.
val jacksonCveFloor = "2.22.1"
allprojects {
    configurations.matching {
        it.name.startsWith("dokkaHtml") && !it.name.endsWith("~internal")
    }.configureEach {
        listOf(
            "com.fasterxml.jackson.core:jackson-databind:$jacksonCveFloor",
            "com.fasterxml.jackson.core:jackson-core:$jacksonCveFloor",
        ).forEach { coordinate ->
            dependencyConstraints.add(
                this@allprojects.dependencies.constraints.create(coordinate) {
                    because("CVE-fixed floor for Jackson on the Dokka Generator worker classpath")
                },
            )
        }
    }
}

// Security pin for the SwiftExport worker classpath: KGP's swift-export-embeddable
// (2.3.20, latest) declares opentelemetry-api 1.41.0, which carries a known CVE.
// keel never runs Swift export, but every KMP subproject exposes this resolvable
// configuration and it lands in the dependency graph. Constrain to the CVE-fixed
// floor, reusing the version-catalog OpenTelemetry version (same line as the
// shipped observability module). Build-tool only — never shipped in keel artifacts.
subprojects {
    configurations.matching { it.name == "swiftExportClasspathResolvable" }.configureEach {
        dependencyConstraints.add(
            this@subprojects.dependencies.constraints.create(
                "io.opentelemetry:opentelemetry-api:${libs.versions.opentelemetry.get()}",
            ) {
                because("CVE-fixed floor for OpenTelemetry pulled in by KGP swift-export-embeddable")
            },
        )
    }
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
    dokka(project(":keel-native-readiness"))
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
    dokka(project(":keel-compression"))
    dokka(project(":keel-compression-zlib"))
    dokka(project(":keel-server"))
    dokka(project(":keel-server-http"))
    dokka(project(":keel-server-websocket"))
    dokka(project(":keel-client-http"))
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
    version = "0.4.3-SNAPSHOT"

    // The custom rules' own suite rides along with every jvmTest run. The
    // rules module is plain JVM, so its task is `test` and no KMP aggregate
    // matches it -- without this wiring the suite that pins the rules'
    // behaviour ran nowhere, and a rule that silently stops matching keeps
    // every detekt task green. Wired here rather than enumerated in CI steps
    // and gate commands: every caller of jvmTest -- CI, both host gates, a
    // developer's plain invocation -- pulls it without knowing it exists.
    // Outside the detekt guard on purpose: a first placement inside it left
    // `:benchmark:jvmTest` and `:sample:jvmTest` outside "every", measured.
    tasks.matching { it.name == "jvmTest" }.configureEach {
        dependsOn(":detekt-rules:test")
    }

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

        // Test sources need a task of their own: nothing else analyses them.
        //
        // detekt's KMP support creates a task per (target, compilation), but only
        // `main` compilations get one for the *intermediate* source sets —
        // `detektMetadataNativeMain` and friends exist, `detektMetadata<X>Test`
        // does not. The per-target `detekt<Target>Test` tasks see only the leaf
        // source set, and this project has no native test code there: it all
        // lives in `commonTest` / `nativeTest` / `macosTest` / `linuxTest` /
        // `appleTest`. Measured before writing this: `detektMacosArm64Test` is
        // NO-SOURCE in every module, the bare `detekt` task likewise, and
        // `detektJvmTest` reports `src/jvmTest` only — a 172-character line
        // planted in `commonTest` was reported by none of them.
        //
        // Hence the explicit source. Two limits worth stating rather than
        // discovering later:
        //
        // - Lint-only, with no classpath. The custom rules are unaffected
        //   (none consults a `bindingContext` -- a count-free claim on purpose:
        //   an added rule must keep it true or lose the lint-only tasks), but
        //   the run as a whole is not:
        //   detekt skips every rule that requires type resolution, so the test
        //   sources are held to a weaker standard than `jvmMain`, which
        //   `detektJvmMain` analyses with types. Giving this task a classpath
        //   means a compilation per target and is a separate decision.
        // - `src/*Test/kotlin` only. Modules excluded from detekt entirely
        //   (`benchmark`, `sample`, `detekt-rules`) are still excluded, and
        //   `detekt-rules` would not match anyway — its tests are a plain-JVM
        //   `src/test/kotlin`, which the pattern does not cover.
        tasks.register<io.gitlab.arturbosch.detekt.Detekt>("detektTestSources") {
            description = "Runs detekt over the KMP test source sets, which no other detekt task covers."
            group = "verification"
            setSource(fileTree("src") { include("*Test/kotlin/**/*.kt") })
            config.setFrom(rootProject.file("detekt.yml"))
            buildUponDefaultConfig = true
            // Same grandfathering contract as the main baseline above: absent
            // means "analyse everything", so a module without one is unaffected.
            val testBaseline = file("detekt-baseline-test.xml")
            if (testBaseline.exists()) baseline.set(testBaseline)
            // The baseline is this task's input and the other task's output, and
            // "regenerate, then check" is the obvious thing to type. Without an
            // ordering Gradle refuses the pair outright -- both tasks fail with
            // "uses this output of task ... without declaring an explicit or
            // implicit dependency", which reads as a detekt problem rather than a
            // wiring one. `mustRunAfter` rather than `dependsOn`: the gate must
            // not regenerate the baseline it is supposed to be checking against.
            mustRunAfter("detektTestSourcesBaseline")
            reports {
                html.required.set(false)
                xml.required.set(false)
                txt.required.set(false)
                sarif.required.set(false)
                md.required.set(false)
            }
        }

        // Writes the baseline the task above reads. Separate from detekt's own
        // `detektBaseline*` tasks, which are generated per (target, compilation)
        // and so have the same blind spot this pair exists to cover.
        tasks.register<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>("detektTestSourcesBaseline") {
            description = "Regenerates the grandfathered baseline for detektTestSources."
            group = "verification"
            setSource(fileTree("src") { include("*Test/kotlin/**/*.kt") })
            config.setFrom(rootProject.file("detekt.yml"))
            buildUponDefaultConfig.set(true)
            baseline.set(file("detekt-baseline-test.xml"))
        }

        // The rulesets are what make `detekt.yml` valid: it configures
        // `formatting:` (detekt-formatting) and `keel:` (detekt-rules), and
        // detekt rejects a config naming a ruleset the run does not have.
        // engine-netty used to be denied them, which is why it could only ever
        // run the bare `detekt` task — and that task is NO-SOURCE here, so the
        // module got no detekt at all.
        //
        // Attaching them is safe. What crashes on Netty's external API is *type
        // resolution* (NPE in IgnoredReturnValue → DescriptorUtilKt.findPackage,
        // detekt 1.23.8), and the tasks that use it stay disabled below. The
        // rulesets themselves do not need it: only the `jvm` target's tasks
        // resolve types (`detektJvmMain` / `detektJvmTest` — their descriptions
        // carry the "with type resolution" suffix and no others do), while
        // `detektJsMain`, `detektMetadata*Main` and `detekt<NativeTarget>Main`
        // are lint-only and already run the custom rules today. Nor can those
        // rules behave differently without it — none of them consults a
        // `bindingContext`; they are purely syntactic. (The former comment here
        // gave "custom rules produce false positives without type resolution"
        // as the reason for the exclusion; that is not what the rules do.)
        //
        // This does NOT give engine-netty full coverage: `detektJvmMain` stays
        // disabled, and the bare `detekt` task is NO-SOURCE against a KMP
        // layout, so the module's 11 `jvmMain` files remain unanalysed. Only
        // its test sources are reached, by the task above.
        dependencies {
            "detektPlugins"(project(":detekt-rules"))
            "detektPlugins"(rootProject.libs.detekt.formatting)
        }
        if (name == "keel-engine-netty") {
            // Disable type resolution tasks to prevent NPE in CI.
            // Only the lint-only tasks are safe to run: the bare `detekt`, and
            // `detektTestSources`, which is lint-only for the same reason and so
            // cannot reach the crash. Keeping it enabled is what lets this
            // module's test sources be analysed at all.
            afterEvaluate {
                val lintOnly = setOf("detekt", "detektTestSources", "detektTestSourcesBaseline")
                tasks.matching { it.name.startsWith("detekt") && it.name !in lintOnly }.configureEach {
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
        // Exempt while the stress opt-in is active, because that is the one
        // state in which a slow test is expected, and the guard's advice
        // ("name it *Benchmark/*Audit and add @Ignore") is wrong for a suite
        // that is already named correctly and already opt-in.
        //
        // Keyed on the opt-in rather than on the class name on purpose -- the
        // guard exists because a name alone cannot be trusted, so exempting by
        // name would hand a free pass to exactly the misnamed measurement code
        // it is looking for.
        //
        // The cost is that the exemption is build-wide: with the variable set,
        // an unfiltered run exempts ordinary tests too. The gate does not hit
        // that (every stress invocation is `--tests`-filtered), and the
        // alternative -- trusting the name -- gives up more.
        val stressOptIn = System.getenv("KEEL_STRESS") != null
        if (!stressOptIn && elapsedSec > SLOW_TEST_BUDGET_SEC) {
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
