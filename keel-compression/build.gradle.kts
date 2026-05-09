plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// keel-compression — protocol-agnostic compression SPI.
//
// Defines `Encoder` / `Decoder` / `CompressionCodec` interfaces along
// with `EncoderOptions` / `DecoderOptions` and a `CompressionRegistry`
// for negotiation. Pure API module — no native dep, no algorithm impl.
//
// Backends live in sibling modules (`keel-compression-zlib` for gzip /
// deflate, `keel-compression-brotli` and `-zstd` are reserved for
// future opt-in additions). Consumers (e.g. `keel-codec-http`,
// `keel-codec-websocket`) depend on this SPI; backend modules are
// pulled in only when an algorithm is actually used (DSL extension
// functions in each backend module wire the codec into a registry).
//
// **SPI scope**: byte-stream compression only. HPACK (RFC 7541) and
// QPACK (RFC 9204) are intentionally out of scope — both are HTTP
// header compression schemes built on Huffman + dynamic table state
// rather than generic byte compression, and live in `keel-codec-http2`
// / `keel-codec-http3` as their own implementations.
//
// History:
//   - Designed in PR #492 (this) alongside the K16 (Native response
//     compression) gate. Pluggable backend SPI emerged from the
//     Phase 11 / `keel-server-http` work — protocols beyond HTTP
//     server response (HTTP client decode, WebSocket permessage-deflate,
//     gRPC per-message) all want to share this SPI.

kotlin {
    jvm()
    js(IR) {
        nodejs()
    }
    linuxX64()
    linuxArm64()
    macosArm64()
    macosX64()

    applyDefaultHierarchyTemplate()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain {
            dependencies {
                // `api` because the SPI exposes `IoBuf` directly in
                // `EncoderSession.update` / `finish` signatures, and
                // backend implementations + consumers reference the
                // type. Avoids a transitive `implementation` declaration
                // in every backend module.
                api(project(":keel-io"))
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
