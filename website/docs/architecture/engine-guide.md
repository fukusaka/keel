---
sidebar_position: 2
---

# Engine Selection Guide

keel provides multiple engine implementations. **The engine is selected at compile time by which `keel-engine-*` Gradle dependency you include** — there is no runtime switching.

In a KMP project, you typically add a different engine dependency per target source set:

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        linuxX64Main.dependencies {
            implementation("io.github.fukusaka.keel:keel-engine-epoll:0.3.0")
        }
        macosArm64Main.dependencies {
            implementation("io.github.fukusaka.keel:keel-engine-kqueue:0.3.0")
        }
        jvmMain.dependencies {
            implementation("io.github.fukusaka.keel:keel-engine-nio:0.3.0")
        }
    }
}
```

All engines implement the same `StreamEngine` interface, so application code is identical across targets.

## Engines

For constructor parameters and configuration options, see the [API reference](pathname:///api/).

### NIO (`keel-engine-nio`)

- **Targets**: `jvm`
- **Use when**: JVM deployment without a Netty dependency. Slightly higher throughput than Netty on large responses
- **TLS**: via `keel-tls-jsse` (always included)

### Netty (`keel-engine-netty`)

- **Targets**: `jvm`
- **Use when**: you already have a Netty dependency in your project, or need Netty's `SslHandler`-based TLS (`NettySslInstaller`)
- **TLS**: via `NettySslInstaller` (Netty `SslHandler`) or `keel-tls-jsse`

### epoll (`keel-engine-epoll`)

- **Targets**: `linuxX64`, `linuxArm64`
- **Use when**: building a Linux server binary. Best general choice on Linux
- **TLS**: via `keel-tls-openssl`, `keel-tls-awslc`, or `keel-tls-mbedtls`

### io_uring (`keel-engine-io-uring`)

- **Targets**: `linuxX64`, `linuxArm64`
- **Use when**: targeting Linux 5.1+ kernels. Throughput is comparable to epoll on `/hello`; the advantage grows with large payloads because io_uring supports zero-copy send (`SEND_ZC`)
- **Requires**: Linux 5.1+ (basic), 5.19+ (multishot accept), 6.0+ (zero-copy send)
- **TLS**: same as epoll

### kqueue (`keel-engine-kqueue`)

- **Targets**: `macosArm64`, `macosX64`
- **Use when**: building a macOS server binary, or developing on M1/M2 Mac
- **TLS**: via `keel-tls-openssl` or `keel-tls-mbedtls`

### NWConnection (`keel-engine-nwconnection`)

- **Targets**: `macosArm64`, `macosX64`
- **Use when**: macOS App Store distribution, or when you want TLS handled entirely by the OS with no certificate management in application code
- **TLS**: built into Network.framework (listener-level, no `keel-tls-*` module needed)

### Node.js (`keel-engine-nodejs`)

- **Targets**: `js` (IR, `nodejs()`)
- **Use when**: targeting the Node.js runtime
- **TLS**: via Node.js built-in `tls` module (listener-level)

## Choosing an Engine

| Platform | Recommended | Alternative | Notes |
|----------|-------------|-------------|-------|
| JVM | Netty | NIO | NIO if you want to avoid the Netty dependency |
| Linux server | epoll | io_uring | io_uring requires Linux 5.1+; choose it if `/large` throughput matters |
| macOS server | kqueue | NWConnection | NWConnection for App Store or OS-managed TLS |
| Node.js | nodejs | — | |

## Development Workflow

A common pattern: **develop on macOS** with kqueue (fast compile, local testing), **deploy to Linux** with epoll or io_uring. Because all engines implement the same interface, no application code changes between environments.

```kotlin
// macosArm64Main — kqueue engine
fun main() = runBlocking {
    val engine = KqueueEngine()
    val server = engine.bind("0.0.0.0", 8080)
    // ...
}

// linuxX64Main — epoll engine, same application code
fun main() = runBlocking {
    val engine = EpollEngine()
    val server = engine.bind("0.0.0.0", 8080)
    // ...
}
```

The engine type in each source set is determined by the `keel-engine-*` dependency in that source set's build configuration.
