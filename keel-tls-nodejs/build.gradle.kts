plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    js(IR) {
        nodejs()
    }

    sourceSets {
        jsMain {
            dependencies {
                implementation(project(":keel-tls"))
            }
        }
        jsTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
