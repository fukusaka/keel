plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()
    js(IR) { nodejs() }
    linuxX64()
    linuxArm64()
    macosArm64()
    macosX64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":keel-core"))
                api(project(":keel-codec-http"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                // A live keelHttpServer on an InMemoryEngine is the fixture the
                // client drives end-to-end (URL -> connect -> codec -> response).
                implementation(project(":keel-server-http"))
                implementation(project(":keel-testing-engine"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        jvmTest {
            dependencies {
                // Real-socket integration: NioEngine gives real DNS + TCP so the
                // URL -> host:port -> connect path is exercised over loopback.
                implementation(project(":keel-engine-nio"))
            }
        }
    }
}
