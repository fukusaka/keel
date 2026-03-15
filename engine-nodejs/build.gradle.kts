plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    js(IR) {
        nodejs()
    }

    sourceSets {
        jsTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
