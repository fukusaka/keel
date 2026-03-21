#!/usr/bin/env bash
# Benchmark all servers: Phase 2 native + Kotlin/Native + JVM
#
# Usage: ./benchmark/bench-all.sh [profile]
#   profile: default (default), tuned, keel-equiv-0.1
#
# Servers are run sequentially, never in parallel.
# Each server is started, warmed up, benchmarked, then killed before the next.

set -uo pipefail
cd "$(dirname "$0")/.."

PROFILE="${1:-default}"
PORT=${BENCH_PORT:-18090}
WRK_THREADS=4
WRK_CONNS=100
WRK_DURATION=10s
ENDPOINT="/hello"
WARMUP_DURATION=3s
READY_TIMEOUT=30

# --- Port management (cross-platform) ---

kill_port() {
    local port="$1"
    if [ "$(uname)" = "Linux" ] && command -v fuser >/dev/null 2>&1; then
        fuser -k "$port"/tcp 2>/dev/null || true
    elif command -v lsof >/dev/null 2>&1; then
        lsof -ti :"$port" 2>/dev/null | xargs kill -9 2>/dev/null || true
    fi
}


# --- Benchmark runner ---

run_bench() {
    local name="$1"
    shift
    local cmd=("$@")

    # Start server. On Linux, use setsid so Gradle child JVM is in the same
    # session and fuser can kill them together.
    if command -v setsid >/dev/null 2>&1; then
        setsid "${cmd[@]}" >/dev/null 2>&1 &
    else
        "${cmd[@]}" >/dev/null 2>&1 &
    fi
    local pid=$!

    # Wait for server to be ready
    local ready=false
    for _ in $(seq 1 "$READY_TIMEOUT"); do
        if curl -s -o /dev/null "http://127.0.0.1:${PORT}${ENDPOINT}" 2>/dev/null; then
            ready=true
            break
        fi
        sleep 0.3
    done

    if [ "$ready" = false ]; then
        printf "  %-24s %s\n" "$name" "FAILED TO START"
        kill_port "$PORT"
        kill "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
        PORT=$((PORT + 1))
        return
    fi

    # Warmup
    wrk -t2 -c10 -d"${WARMUP_DURATION}" "http://127.0.0.1:${PORT}${ENDPOINT}" >/dev/null 2>&1

    # Benchmark
    local result
    result=$(wrk -t"${WRK_THREADS}" -c"${WRK_CONNS}" -d"${WRK_DURATION}" --latency "http://127.0.0.1:${PORT}${ENDPOINT}" 2>&1)

    local rps lat50 lat99 errors
    rps=$(echo "$result" | grep "Requests/sec" | awk '{print $2}')
    lat50=$(echo "$result" | grep "50%" | awk '{print $2}')
    lat99=$(echo "$result" | grep "99%" | awk '{print $2}')
    errors=$(echo "$result" | grep "Socket errors" | head -1)

    printf "  %-24s %12s req/s  p50=%-10s p99=%-10s" "$name" "$rps" "$lat50" "$lat99"
    if [ -n "$errors" ]; then
        echo "  $errors"
    else
        echo ""
    fi

    # Stop server: kill by port first (catches Gradle child JVM),
    # then kill PID as fallback. Move to next port to avoid TIME_WAIT.
    kill_port "$PORT"
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    PORT=$((PORT + 1))
}

# --- Run optional binary (skip if not found) ---

run_if_exists() {
    local name="$1" binary="$2"
    shift 2
    if [ -f "$binary" ]; then
        run_bench "$name" "$binary" "$@"
    fi
}

# --- Main ---

PROFILE_ARGS=""
if [ "$PROFILE" != "default" ]; then
    PROFILE_ARGS="--profile=${PROFILE}"
fi

echo "=== Benchmark: ${ENDPOINT} (${WRK_THREADS}t/${WRK_CONNS}c/${WRK_DURATION}) profile=${PROFILE} ==="
echo ""
printf "  %-24s %12s        %-10s  %-10s\n" "Server" "Req/sec" "p50" "p99"
printf "  %-24s %12s        %-10s  %-10s\n" "------------------------" "------------" "----------" "----------"

# Phase 2: Native servers (skip if binary not found)
run_if_exists "rust-hello" benchmark/rust-hello/target/release/rust-hello --port="${PORT}" ${PROFILE_ARGS}
run_if_exists "go-hello" benchmark/go-hello/go-hello --port="${PORT}" ${PROFILE_ARGS}
run_if_exists "swift-hello" benchmark/swift-hello/.build/release/swift-hello --port="${PORT}" ${PROFILE_ARGS}
run_if_exists "zig-hello" benchmark/zig-hello/zig-out/bin/zig-hello --port="${PORT}" ${PROFILE_ARGS}

# Kotlin/Native servers
NATIVE_BIN=""
if [ "$(uname)" = "Darwin" ]; then
    ARCH=$(uname -m)
    if [ "$ARCH" = "arm64" ]; then
        NATIVE_BIN="benchmark/build/bin/macosArm64/releaseExecutable/benchmark.kexe"
    else
        NATIVE_BIN="benchmark/build/bin/macosX64/releaseExecutable/benchmark.kexe"
    fi
    if [ -f "$NATIVE_BIN" ]; then
        for engine in keel-kqueue keel-nwconnection ktor-cio; do
            run_bench "native:${engine}" "$NATIVE_BIN" --engine="${engine}" --port="${PORT}" ${PROFILE_ARGS}
        done
    fi
elif [ "$(uname)" = "Linux" ]; then
    NATIVE_BIN="benchmark/build/bin/linuxX64/releaseExecutable/benchmark.kexe"
    if [ -f "$NATIVE_BIN" ]; then
        for engine in keel-epoll ktor-cio; do
            run_bench "native:${engine}" "$NATIVE_BIN" --engine="${engine}" --port="${PORT}" ${PROFILE_ARGS}
        done
    fi
fi

# JVM servers — use classpath file to avoid Gradle process tree issues.
# Gradle spawns a child JVM for JavaExec; kill only reaches the wrapper, not the server.
# Using java -cp directly creates a single process that responds to signals.
JVM_CP_FILE="benchmark/build/benchmark-classpath.txt"
if [ -f "$JVM_CP_FILE" ]; then
    JVM_CP=$(cat "$JVM_CP_FILE")
    for engine in keel-nio keel-netty ktor-cio ktor-netty netty-raw spring vertx; do
        run_bench "jvm:${engine}" java -cp "$JVM_CP" io.github.keel.benchmark.JvmMainKt --engine="${engine}" --port="${PORT}" ${PROFILE_ARGS}
    done
else
    echo "  (classpath file not found — run './gradlew -Pbenchmark :benchmark:writeClasspath' first)"
fi

echo ""
echo "=== Done ==="
