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
    ":io-core",
    ":core",
    ":engine-epoll",
    ":engine-kqueue",
    ":engine-nio",
    ":engine-netty",
    ":engine-nodejs",
    ":engine-nwconnection",
    ":codec-http",
    ":codec-websocket",
    ":ktor-engine",
)

// Benchmark and sample modules are opt-in to avoid downloading
// Spring Boot, Vert.x, etc. during normal builds.
//   ./gradlew -Pbenchmark :benchmark:run --args="--engine=keel"
//   ./gradlew -Pbenchmark :sample:run
if (providers.gradleProperty("benchmark").isPresent) {
    include(":benchmark", ":sample")
}
