plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Forward the `keel.stress` system property to the test JVM so
// `NettyPipelineWsStressTest` can opt itself in via
// `assumeTrue(System.getProperty("keel.stress") == "true")`. Gradle's
// default `Test` task does not propagate `-D` properties from the build
// JVM to the forked test JVM. Without this forwarding, the stress test
// stays skipped even when the workflow runs `./gradlew … -Dkeel.stress=true`.
//
// `withType<Test>().configureEach` is used (rather than a named lookup on
// `jvmTest`) because the KMP plugin registers test tasks lazily — a direct
// `tasks.named("jvmTest")` at the script top level fires before the task
// exists. The `configureEach` form runs once per Test task as it is
// realised, after the KMP plugin has registered it.
tasks.withType<Test>().configureEach {
    systemProperty("keel.stress", System.getProperty("keel.stress") ?: "false")
}

kotlin {
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":keel-core"))
            }
        }
        jvmMain {
            dependencies {
                implementation(project(":keel-tls"))
                implementation(project(":keel-server"))
                implementation(libs.netty.all)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.io.core)
                implementation(project(":keel-codec-http"))
                implementation(project(":keel-codec-websocket"))
                implementation(project(":keel-io"))
                // Shared test fixtures (`TestHttpClient` + `newTestHttpClient`,
                // replacing the inline `TestWsClient` private classes that
                // NettyPipelineWsEchoTest / NettyPipelineWsStressTest used to
                // duplicate from PR #483 / #486).
                implementation(project(":keel-testing-common"))
            }
        }
    }
}
