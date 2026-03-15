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
    ":core",
    ":engine-epoll",
    ":engine-kqueue",
    ":engine-nio",
    ":engine-netty",
    ":engine-nodejs",
    ":engine-nwconnection",
)
