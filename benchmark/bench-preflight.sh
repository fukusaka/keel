#!/usr/bin/env bash
# Sourced helper — pre-flight validation for `bench-all.sh` and
# `bench-stream-all.sh` to refuse silent partial sweeps.
#
# Why this exists: the 2026-06-19 fresh baseline sweep produced zero Node.js
# rows because the JS production binary was never built; both sweep scripts
# silent-skipped the JS engines and the result was eight HTTP /hello+/large
# Node.js cells left as `要再計測` in `status.md`. The 2026-06-19 stage 10
# streaming sweep then repeated the same gap for the streaming scenarios
# (`upload` / `sse` / `multipart` / `method-mix` / `path-param` /
# `slow-upload`) because `bench-stream-all.sh` followed the same silent-skip
# shape. PR #816 added per-script `WARN:` lines but did not stop the sweep
# from running, so stale cells persisted across subsequent passes.
#
# This pre-flight check turns the WARN into a hard failure by default: a
# sweep cannot start unless all *primary* engine binaries (native kexe for
# the host, JVM classpath file, JS production bin) exist. The Native /
# JVM / JS triple is the floor — losing any one of them leaves the result
# table with structural holes that future readers cannot tell apart from
# legitimate `—` cells.
#
# Cross-language reference servers (`rust-bench` / `go-bench` /
# `zig-bench` / `swift-bench`) are intentionally **not** checked here:
# they are optional adversaries from outside the keel codebase and
# silent-skipping them does not produce stale rows in the keel engine
# tables (they get their own dedicated rows). The same is true for the
# `ktor-cio` / `ktor-netty` / `netty-raw` / `spring` / `vertx` JVM refs
# which share the JVM classpath check anyway.
#
# Opt-out: set `BENCH_SKIP_MISSING_BINARIES=true` if you really need a
# partial sweep (e.g. quick smoke during a JS gradle reconfigure). The
# default is fail-fast so the missing binary cannot be silently absorbed
# into the result table.

preflight_check_primary_binaries() {
    local missing=()

    # Native kexe — host-appropriate. The Linux Apple Silicon case is
    # intentionally not enumerated: keel does not target it.
    if [ "$(uname)" = "Darwin" ]; then
        local arch
        arch=$(uname -m)
        if [ "$arch" = "arm64" ]; then
            local native_bin="benchmark/build/bin/macosArm64/releaseExecutable/benchmark.kexe"
            local native_task="linkReleaseExecutableMacosArm64"
        else
            local native_bin="benchmark/build/bin/macosX64/releaseExecutable/benchmark.kexe"
            local native_task="linkReleaseExecutableMacosX64"
        fi
        if [ ! -f "$native_bin" ]; then
            missing+=("native ($native_bin) — ./gradlew -Pbenchmark :benchmark:${native_task}")
        fi
    elif [ "$(uname)" = "Linux" ]; then
        local native_bin="benchmark/build/bin/linuxX64/releaseExecutable/benchmark.kexe"
        if [ ! -f "$native_bin" ]; then
            missing+=("native ($native_bin) — ./gradlew -Pbenchmark :benchmark:linkReleaseExecutableLinuxX64")
        fi
    fi

    # JVM classpath file must resolve on this host (the file uses
    # ${REPO_ROOT} / ${GRADLE_USER_HOME} placeholders that `bench-jvm-cp.sh
    # resolve` expands locally — see benchmarking.md). A missing or
    # un-resolvable classpath silently drops all JVM engines, matching the
    # Node.js pattern this preflight is here to prevent.
    if ! ./benchmark/bench-jvm-cp.sh resolve >/dev/null 2>&1; then
        missing+=("jvm classpath — ./gradlew -Pbenchmark :benchmark:writeClasspath")
    fi

    # JS production bin — built by a separate task that does not transitively
    # fire from the Native / JVM rebuilds.
    local js_bin="benchmark/build/compileSync/js/main/productionExecutable/kotlin/keel-benchmark.js"
    if [ ! -f "$js_bin" ]; then
        missing+=("js ($js_bin) — ./gradlew -Pbenchmark :benchmark:compileProductionExecutableKotlinJs")
    fi

    if [ "${#missing[@]}" -eq 0 ]; then
        return 0
    fi

    {
        echo ""
        echo "============================================================"
        echo "Pre-flight check failed: primary engine binaries missing."
        echo ""
        for entry in "${missing[@]}"; do
            echo "  MISSING: $entry"
        done
        echo ""
        echo "Silent-skipping these binaries is what left the Node.js"
        echo "streaming cells stale through 2026-06-19. Rebuild all"
        echo "primary binaries before sweeping — or set"
        echo "BENCH_SKIP_MISSING_BINARIES=true for an explicit partial"
        echo "sweep (the resulting table will be missing rows)."
        echo "============================================================"
        echo ""
    } >&2

    if [ "${BENCH_SKIP_MISSING_BINARIES:-false}" = "true" ]; then
        echo "BENCH_SKIP_MISSING_BINARIES=true — continuing with available engines only." >&2
        return 0
    fi
    exit 1
}
