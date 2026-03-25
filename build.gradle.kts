plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.detekt)
}

dependencies {
    detektPlugins(project(":detekt-rules"))
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

detekt {
    // Analyze all Kotlin source sets across modules (excluding benchmark/sample)
    source.setFrom(
        subprojects.filter { it.name !in setOf("benchmark", "sample", "detekt-rules") }.flatMap { project ->
            listOf(
                "${project.projectDir}/src/commonMain/kotlin",
                "${project.projectDir}/src/jvmMain/kotlin",
                "${project.projectDir}/src/nativeMain/kotlin",
                "${project.projectDir}/src/macosMain/kotlin",
                "${project.projectDir}/src/linuxMain/kotlin",
                "${project.projectDir}/src/jsMain/kotlin",
            ).map { file(it) }
        },
    )
    config.setFrom("detekt.yml")
    buildUponDefaultConfig = true
    // Baseline for existing violations — new code must be clean
    // baseline = file("detekt-baseline.xml")
}

subprojects {
    apply(plugin = "org.jetbrains.dokka")
    group = "io.github.fukusaka.keel"
    version = "0.2.0-SNAPSHOT"
}
