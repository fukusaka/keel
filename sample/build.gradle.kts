plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val hostOs: String = System.getProperty("os.name").lowercase()
val isMacHost: Boolean = hostOs.contains("mac")

kotlin {
    applyDefaultHierarchyTemplate()

    jvm()

    // Native macOS sample for the NWConnection engine BufferAllocator.lifecycleListener
    // wiring (item 12 B2.5 step 3). Host-gated because NWConnection cinterop is macOS-only.
    if (isMacHost) {
        macosArm64 {
            binaries {
                executable("NwListenerSample") {
                    entryPoint = "io.github.fukusaka.keel.sample.observability.main"
                }
            }
        }
    }

    sourceSets {
        if (isMacHost) {
            macosArm64Main {
                dependencies {
                    implementation(project(":keel-io"))
                    implementation(project(":keel-core"))
                    implementation(project(":keel-engine-nwconnection"))
                    implementation(libs.kotlinx.coroutines.core)
                }
            }
        }
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
                // Netty engine + kotlinx.coroutines for the Netty-side
                // BufferAllocator.lifecycleListener visual-verification
                // sample (item 12 B2.5 step 2). Drives an echo workload
                // through NettyEngine and prints the TrackingAllocator
                // listener counters proving engine-direct NettyByteBufIoBuf
                // events are observable.
                implementation(project(":keel-engine-netty"))
                implementation(libs.kotlinx.coroutines.core)
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

tasks.register<JavaExec>("runNettyListenerSample") {
    description = "Run the NettyEngine BufferAllocator.lifecycleListener visual-verification sample. " +
        "Drives an echo workload through NettyEngine + TrackingAllocator listener and prints the live counters."
    group = "application"
    mainClass.set("io.github.fukusaka.keel.sample.observability.NettyEngineListenerSampleKt")
    classpath = kotlin.jvm().compilations["main"].runtimeDependencyFiles +
        kotlin.jvm().compilations["main"].output.allOutputs
    standardInput = System.`in`
}
