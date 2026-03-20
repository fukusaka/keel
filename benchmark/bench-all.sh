#!/usr/bin/env bash
# Quick benchmark across all native + JVM servers
# Usage: ./benchmark/bench-all.sh [profile]
#   profile: default (default), tuned, keel-equiv-0.1

set -euo pipefail
cd "$(dirname "$0")/.."

PROFILE="${1:-default}"
PORT=18090
WRK_THREADS=4
WRK_CONNS=100
WRK_DURATION=10s
ENDPOINT="/hello"
WARMUP=3s

echo "=== Benchmark: ${ENDPOINT} (${WRK_THREADS}t/${WRK_CONNS}c/${WRK_DURATION}) profile=${PROFILE} ==="
echo ""

run_bench() {
    local name="$1"
    shift
    local cmd=("$@")

    # Start server
    "${cmd[@]}" &
    local pid=$!

    # Wait for ready
    local ready=false
    for i in $(seq 1 30); do
        if curl -s -o /dev/null "http://127.0.0.1:${PORT}${ENDPOINT}" 2>/dev/null; then
            ready=true
            break
        fi
        sleep 0.3
    done

    if [ "$ready" = false ]; then
        echo "  $name: FAILED TO START"
        kill "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
        return
    fi

    # Warmup
    wrk -t2 -c10 -d${WARMUP} "http://127.0.0.1:${PORT}${ENDPOINT}" >/dev/null 2>&1

    # Benchmark
    local result
    result=$(wrk -t${WRK_THREADS} -c${WRK_CONNS} -d${WRK_DURATION} --latency "http://127.0.0.1:${PORT}${ENDPOINT}" 2>&1)

    local rps=$(echo "$result" | grep "Requests/sec" | awk '{print $2}')
    local lat50=$(echo "$result" | grep "50%" | awk '{print $2}')
    local lat99=$(echo "$result" | grep "99%" | awk '{print $2}')
    local errors=$(echo "$result" | grep "Socket errors" | head -1)

    printf "  %-18s %12s req/s  p50=%-10s p99=%-10s" "$name" "$rps" "$lat50" "$lat99"
    if [ -n "$errors" ]; then
        echo "  $errors"
    else
        echo ""
    fi

    # Stop
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    sleep 0.5
}

PROFILE_ARGS=""
if [ "$PROFILE" != "default" ]; then
    PROFILE_ARGS="--profile=${PROFILE}"
fi

printf "  %-18s %12s        %-10s  %-10s\n" "Server" "Req/sec" "p50" "p99"
printf "  %-18s %12s        %-10s  %-10s\n" "------------------" "------------" "----------" "----------"

# Native servers
run_bench "rust-hello" benchmark/rust-hello/target/release/rust-hello --port=${PORT} ${PROFILE_ARGS}
run_bench "go-hello" benchmark/go-hello/go-hello --port=${PORT} ${PROFILE_ARGS}
run_bench "swift-hello" benchmark/swift-hello/.build/release/swift-hello --port=${PORT} ${PROFILE_ARGS}
run_bench "zig-hello" benchmark/zig-hello/zig-out/bin/zig-hello --port=${PORT} ${PROFILE_ARGS}

# JVM servers (if benchmark module available)
if [ -f "gradlew" ]; then
    for engine in keel-nio keel-netty ktor-cio ktor-netty netty-raw spring vertx; do
        run_bench "jvm:${engine}" ./gradlew -Pbenchmark --no-daemon -q :benchmark:run --args="--engine=${engine} --port=${PORT} ${PROFILE_ARGS}"
    done
fi

echo ""
echo "=== Done ==="
