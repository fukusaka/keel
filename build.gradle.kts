plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.detekt)
}

dependencies {
    dokka(project(":core"))
    dokka(project(":engine-epoll"))
    dokka(project(":engine-kqueue"))
    dokka(project(":engine-nio"))
    dokka(project(":engine-netty"))
    dokka(project(":engine-nodejs"))
    dokka(project(":engine-nwconnection"))
    dokka(project(":codec-http"))
    dokka(project(":codec-websocket"))
}

subprojects {
    apply(plugin = "org.jetbrains.dokka")
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
        // Custom rules (NativeBufLeak etc.) are excluded because they
        // produce false positives without type resolution.
        // Type resolution tasks (detektJvmMain etc.) must NOT be run for
        // this module — use `detekt` task only.
        if (name != "engine-netty") {
            dependencies {
                "detektPlugins"(project(":detekt-rules"))
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
