#!/usr/bin/env bash
# Benchmark keel engines only (ktor-keel-* + ktor-cio for comparison)
#
# Usage: ./benchmark/bench-keel.sh [profile]
#
# Runs only keel-related engines, skipping Phase 2 native servers
# and non-keel JVM servers (spring, vertx, netty-raw).

set -uo pipefail
cd "$(dirname "$0")/.."

PROFILE="${1:-default}"
PORT=${BENCH_PORT:-18090}
WRK_THREADS=4
WRK_CONNS=100
WRK_DURATION=10s
ENDPOINT="${BENCH_ENDPOINT:-/hello}"
WARMUP_DURATION=3s
READY_TIMEOUT=30
RESULTS_BASE="benchmark/results"
HOST_LABEL="${BENCH_HOST_LABEL:-$(hostname -s)}"
RESULTS_DIR="${RESULTS_BASE}/${HOST_LABEL}"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)

mkdir -p "$RESULTS_DIR"

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

    if command -v setsid >/dev/null 2>&1; then
        setsid "${cmd[@]}" >/dev/null 2>&1 &
    else
        "${cmd[@]}" >/dev/null 2>&1 &
    fi
    local pid=$!

    local ready=false
    for _ in $(seq 1 "$READY_TIMEOUT"); do
        if curl -s -o /dev/null "http://127.0.0.1:${PORT}${ENDPOINT}" 2>/dev/null; then
            ready=true
            break
        fi
        sleep 0.3
    done

    if [ "$ready" = false ]; then
        printf "  %-28s %s\n" "$name" "FAILED TO START"
        kill_port "$PORT"
        kill "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
        PORT=$((PORT + 1))
        return
    fi

    wrk -t2 -c10 -d"${WARMUP_DURATION}" "http://127.0.0.1:${PORT}${ENDPOINT}" >/dev/null 2>&1

    local result
    result=$(wrk -t"${WRK_THREADS}" -c"${WRK_CONNS}" -d"${WRK_DURATION}" --latency "http://127.0.0.1:${PORT}${ENDPOINT}" 2>&1)

    # Save raw wrk output
    local safe_name
    safe_name=$(echo "$name" | tr ':/' '-')
    local endpoint_name
    endpoint_name=$(echo "$ENDPOINT" | tr '/' '-' | sed 's/^-//')
    local result_file="${RESULTS_DIR}/${safe_name}-${endpoint_name}-${WRK_THREADS}t${WRK_CONNS}c-${TIMESTAMP}.txt"
    echo "$result" > "$result_file"

    local rps lat50 lat99 errors
    rps=$(echo "$result" | grep "Requests/sec" | awk '{print $2}')
    lat50=$(echo "$result" | grep "50%" | awk '{print $2}')
    lat99=$(echo "$result" | grep "99%" | awk '{print $2}')
    errors=$(echo "$result" | grep "Socket errors" | head -1)

    printf "  %-28s %12s req/s  p50=%-10s p99=%-10s" "$name" "$rps" "$lat50" "$lat99"
    if [ -n "$errors" ]; then
        echo "  $errors"
    else
        echo ""
    fi

    kill_port "$PORT"
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    PORT=$((PORT + 1))
}

# --- Main ---

PROFILE_ARGS=""
if [ "$PROFILE" != "default" ]; then
    PROFILE_ARGS="--profile=${PROFILE}"
fi

echo "=== keel Benchmark: ${ENDPOINT} (${WRK_THREADS}t/${WRK_CONNS}c/${WRK_DURATION}) profile=${PROFILE} ==="
echo ""
printf "  %-28s %12s        %-10s  %-10s\n" "Server" "Req/sec" "p50" "p99"
printf "  %-28s %12s        %-10s  %-10s\n" "----------------------------" "------------" "----------" "----------"

# Kotlin/Native engines
NATIVE_BIN=""
if [ "$(uname)" = "Darwin" ]; then
    ARCH=$(uname -m)
    if [ "$ARCH" = "arm64" ]; then
        NATIVE_BIN="benchmark/build/bin/macosArm64/releaseExecutable/benchmark.kexe"
    else
        NATIVE_BIN="benchmark/build/bin/macosX64/releaseExecutable/benchmark.kexe"
    fi
    if [ -f "$NATIVE_BIN" ]; then
        for engine in ktor-keel-kqueue ktor-keel-nwconnection ktor-cio; do
            run_bench "native:${engine}" "$NATIVE_BIN" --engine="${engine}" --port="${PORT}" ${PROFILE_ARGS}
        done
    fi
elif [ "$(uname)" = "Linux" ]; then
    NATIVE_BIN="benchmark/build/bin/linuxX64/releaseExecutable/benchmark.kexe"
    if [ -f "$NATIVE_BIN" ]; then
        for engine in ktor-keel-epoll ktor-cio; do
            run_bench "native:${engine}" "$NATIVE_BIN" --engine="${engine}" --port="${PORT}" ${PROFILE_ARGS}
        done
    fi
fi

# JVM keel engines + ktor-cio for comparison
JVM_CP_FILE="benchmark/build/benchmark-classpath.txt"
if [ -f "$JVM_CP_FILE" ]; then
    JVM_CP=$(cat "$JVM_CP_FILE")
    for engine in ktor-keel-nio ktor-keel-netty ktor-cio; do
        run_bench "jvm:${engine}" java -cp "$JVM_CP" io.github.fukusaka.keel.benchmark.JvmMainKt --engine="${engine}" --port="${PORT}" ${PROFILE_ARGS}
    done
else
    echo "  (classpath file not found — run './gradlew -Pbenchmark :benchmark:writeClasspath' first)"
fi

echo ""
echo "=== Done ==="
