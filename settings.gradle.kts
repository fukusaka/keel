rootProject.name = "keel"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

val hostOs: String = System.getProperty("os.name").lowercase()
val isLinuxHost: Boolean = hostOs.contains("linux")
val isMacHost: Boolean = hostOs.contains("mac")

include(
    ":keel-io",
    ":keel-core",
    ":keel-native-posix",
    ":keel-native-posix-testing",
    ":keel-engine-nio",
    ":keel-engine-netty",
    ":keel-engine-nodejs",
    ":keel-codec-http",
    ":keel-codec-websocket",
    ":keel-tls",
    ":keel-tls-jsse",
    ":keel-ktor-engine",
    ":detekt-rules",
)

// Linux-only engine modules: their cinterops (epoll, io_uring, posix_inet,
// liburing) require Linux kernel headers unavailable on macOS / Windows
// hosts, and Kotlin/Native does not support cinterop cross-compilation.
// Including them on non-Linux hosts causes `./gradlew assemble` and any
// task that cascades through compileLinuxMainKotlinMetadata to fail.
// Consumers (:keel-ktor-engine, :benchmark) host-gate their dependency
// on these modules symmetrically so references only trigger when the
// current host can actually build them.
if (isLinuxHost) {
    include(":keel-engine-epoll", ":keel-engine-io-uring")
}

// macOS-only engine modules (kqueue, Network.framework) — symmetric
// rationale to the Linux case above.
if (isMacHost) {
    include(":keel-engine-kqueue", ":keel-engine-nwconnection")
}

// Benchmark and sample modules are opt-in to avoid downloading
// Spring Boot, Vert.x, etc. during normal builds.
//   ./gradlew -Pbenchmark :benchmark:run --args="--engine=keel"
//   ./gradlew -Pbenchmark :sample:run
if (providers.gradleProperty("benchmark").isPresent) {
    include(":benchmark", ":sample")
}

// TLS modules with native library dependencies — opt-in to avoid
// cinterop link errors on machines without the required libraries.
//   ./gradlew -Ptls :keel-tls-mbedtls:macosArm64Test
if (providers.gradleProperty("tls").isPresent) {
    include(":keel-tls-mbedtls", ":keel-tls-openssl", ":keel-tls-awslc", ":keel-tls-nodejs")
}
