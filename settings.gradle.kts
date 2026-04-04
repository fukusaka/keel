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
    ":logging",
    ":io-core",
    ":core",
    ":engine-epoll",
    ":engine-io-uring",
    ":engine-kqueue",
    ":engine-nio",
    ":engine-netty",
    ":engine-nodejs",
    ":engine-nwconnection",
    ":codec-http",
    ":codec-websocket",
    ":tls",
    ":tls-mbedtls",
    ":ktor-engine",
    ":detekt-rules",
)

// Benchmark and sample modules are opt-in to avoid downloading
// Spring Boot, Vert.x, etc. during normal builds.
//   ./gradlew -Pbenchmark :benchmark:run --args="--engine=keel"
//   ./gradlew -Pbenchmark :sample:run
if (providers.gradleProperty("benchmark").isPresent) {
    include(":benchmark", ":sample")
}

// TLS experiment modules — opt-in.
//   ./gradlew -Ptls :tls-mbedtls:macosArm64Test
if (providers.gradleProperty("tls").isPresent) {
    include(":tls-openssl", ":tls-awslc", ":tls-jsse", ":tls-nodejs")
}
