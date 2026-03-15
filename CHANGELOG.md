# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed

- CI trigger on PRs changed from every push to label-based: `needs-pr-check` label required to run CI

### Added

- `IoEngine` and `NativeBuf` as `expect class` in `commonMain`
  - JVM actual: `NativeBuf` backed by `ByteBuffer.allocateDirect`
  - Native actual: `NativeBuf` backed by `nativeHeap.allocArray<ByteVar>`
- KMP multi-project scaffold: Gradle 9.4, 5 modules (`core`, `engine-epoll`, `engine-kqueue`, `engine-nio`, `engine-netty`)
- KMP targets: `jvm`, `linuxX64`, `macosArm64` (`applyDefaultHierarchyTemplate` for `nativeMain`)
- GitHub Actions CI workflow (`ubuntu-latest`): `compileKotlinJvm`, `compileKotlinLinuxX64`, `jvmTest`
- `scripts/check-local.sh`: macosArm64 pre-PR validation script (macOS runner 不使用の代替)
- `engine-kqueue`: kqueue cinterop definition (`kqueue.def`)
  - Binds `sys/event.h` with `-D_DARWIN_C_SOURCE`
  - `keel_ev_set()` wrapper for `EV_SET` C macro via cinterop glue code
  - Targets restricted to `macosArm64` / `macosX64`
- `gradle.properties`: `kotlin.mpp.enableCInteropCommonization=true` to expose kqueue types in `macosMain`
