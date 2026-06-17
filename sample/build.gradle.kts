plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()

    sourceSets {
        jvmMain {
            dependencies {
                implementation(project(":keel-core"))
                implementation(project(":keel-server-ktor"))
                implementation(project(":keel-engine-nio"))
                implementation(libs.ktor.server.core)
                // BufferAllocator observer hook + OpenTelemetry adapter for
                // the observability sample. The sample wires keel's
                // PooledDirectAllocator into OpenTelemetry via the adapter
                // module and emits to whatever endpoint
                // OTEL_EXPORTER_OTLP_ENDPOINT points at (SigNoz on
                // http://localhost:4317 by default).
                implementation(project(":keel-io"))
                implementation(project(":keel-observability-opentelemetry"))
                implementation(libs.opentelemetry.api)
                implementation(libs.opentelemetry.sdk)
                implementation(libs.opentelemetry.exporter.otlp)
                implementation(libs.opentelemetry.sdk.extension.autoconfigure)
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

tasks.register<JavaExec>("runObservabilitySample") {
    description = "Run the BufferAllocator + OpenTelemetry observability sample. " +
        "Set OTEL_EXPORTER_OTLP_ENDPOINT (default http://localhost:4317) to point at SigNoz / OT Collector / etc."
    group = "application"
    mainClass.set("io.github.fukusaka.keel.sample.observability.BufferAllocatorOtelSampleKt")
    classpath = kotlin.jvm().compilations["main"].runtimeDependencyFiles +
        kotlin.jvm().compilations["main"].output.allOutputs
    standardInput = System.`in`
}
