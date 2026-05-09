plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// keel-compression-zlib — gzip + deflate backend.
//
// Implements the `keel-compression` SPI (Encoder / Decoder /
// CompressionCodec) for the gzip (RFC 1952) and deflate (RFC 1951 raw,
// RFC 1950 zlib-wrapped) content encodings. Three platform impls share
// the common SPI:
//
//   - JVM:     `java.util.zip.Deflater` / `Inflater`
//   - Native:  cinterop with `libz` (macOS / Linux ships it by default)
//   - JS:      Node `zlib` module
//
// Exposed as two `CompressionCodec` singletons:
//
//   - `GzipCodec`     — `Content-Encoding: gzip`,    RFC 1952 framing
//   - `DeflateCodec`  — `Content-Encoding: deflate`, RFC 1950 framing
//
// `WrapFormat.Raw` is also supported (used by WebSocket
// `permessage-deflate`); it can be requested by passing
// `EncoderOptions(wrapFormat = WrapFormat.Raw)` to either codec's
// encoder, which yields raw RFC 1951 bits without a wrapper.

fun org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.configureZlibCinterop() {
    compilations["main"].cinterops {
        create("keel_zlib") {
            defFile("src/nativeInterop/cinterop/keel_zlib.def")
        }
    }
}

kotlin {
    jvm()
    js(IR) {
        nodejs()
    }
    linuxX64 { configureZlibCinterop() }
    linuxArm64 { configureZlibCinterop() }
    macosArm64 { configureZlibCinterop() }
    macosX64 { configureZlibCinterop() }

    applyDefaultHierarchyTemplate()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":keel-compression"))
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":keel-io"))
            }
        }
    }
}
