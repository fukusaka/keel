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

include(
    ":keel-io",
    ":keel-core",
    ":keel-native-posix",
    ":keel-engine-epoll",
    ":keel-engine-io-uring",
    ":keel-engine-kqueue",
    ":keel-engine-nio",
    ":keel-engine-netty",
    ":keel-engine-nodejs",
    ":keel-engine-nwconnection",
    ":keel-codec-http",
    ":keel-codec-websocket",
    ":keel-tls",
    ":keel-tls-jsse",
    ":keel-ktor-engine",
    ":detekt-rules",
)

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
