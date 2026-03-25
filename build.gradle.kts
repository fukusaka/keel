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

    // Apply detekt with type resolution to production modules
    if (name !in setOf("benchmark", "sample", "detekt-rules")) {
        apply(plugin = "io.gitlab.arturbosch.detekt")
        configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            config.setFrom(rootProject.file("detekt.yml"))
            buildUponDefaultConfig = true
        }
        dependencies {
            "detektPlugins"(project(":detekt-rules"))
        }
    }
}
