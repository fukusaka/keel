plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.dokka)
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
    group = "io.github.keel"
    version = "0.1.0-SNAPSHOT"
}
