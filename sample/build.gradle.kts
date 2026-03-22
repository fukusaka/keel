plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()

    sourceSets {
        jvmMain {
            dependencies {
                implementation(project(":ktor-engine"))
                implementation(libs.ktor.server.core)
            }
        }
    }
}

tasks.register<JavaExec>("run") {
    description = "Run the keel sample server"
    mainClass.set("io.github.fukusaka.keel.sample.MainKt")
    classpath = kotlin.jvm().compilations["main"].runtimeDependencyFiles +
        kotlin.jvm().compilations["main"].output.allOutputs
    standardInput = System.`in`
}
