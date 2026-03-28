#!/usr/bin/env bash
# Benchmark a single server
#
# Usage: ./benchmark/bench-one.sh <name> <command> [args...]
#
# Example:
#   ./benchmark/bench-one.sh rust-hello benchmark/rust-hello/target/release/rust-hello --port=18090
#   ./benchmark/bench-one.sh jvm:keel-nio ./gradlew -Pbenchmark --no-daemon -q :benchmark:run --args="--engine=keel-nio --port=18090"

set -uo pipefail

NAME="${1:?Usage: bench-one.sh <name> <command> [args...]}"
shift

PORT=18090
WRK_THREADS=${BENCH_WRK_THREADS:-4}
WRK_CONNS=${BENCH_WRK_CONNS:-100}
WRK_DURATION=${BENCH_WRK_DURATION:-10s}
ENDPOINT="${BENCH_ENDPOINT:-/hello}"
WARMUP_DURATION=3s
READY_TIMEOUT=60

# Extract --port=N from args if present
for arg in "$@"; do
    case "$arg" in
        --port=*) PORT="${arg#--port=}" ;;
    esac
done

# --- Port management (cross-platform) ---

kill_port() {
    local port="$1"
    if [ "$(uname)" = "Linux" ] && command -v fuser >/dev/null 2>&1; then
        fuser -k "$port"/tcp 2>/dev/null || true
    elif command -v lsof >/dev/null 2>&1; then
        lsof -ti :"$port" 2>/dev/null | xargs kill -9 2>/dev/null || true
    fi
}

# Kill any existing process on the port
kill_port "$PORT"
sleep 1

# Start server. On Linux, use setsid so Gradle child JVM is in the same
# session and fuser can kill them together.
if command -v setsid >/dev/null 2>&1; then
    setsid "$@" >/dev/null 2>&1 &
else
    "$@" >/dev/null 2>&1 &
fi
PID=$!

# Wait for server to be ready
READY=false
for _ in $(seq 1 "$READY_TIMEOUT"); do
    if curl -s -o /dev/null "http://127.0.0.1:${PORT}${ENDPOINT}" 2>/dev/null; then
        READY=true
        break
    fi
    sleep 0.5
done

if [ "$READY" = false ]; then
    echo "$NAME|FAILED|-|-"
    kill_port "$PORT"
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
    exit 1
fi

# Warmup
wrk -t2 -c10 -d"${WARMUP_DURATION}" "http://127.0.0.1:${PORT}${ENDPOINT}" >/dev/null 2>&1

# Benchmark
RESULT=$(wrk -t"${WRK_THREADS}" -c"${WRK_CONNS}" -d"${WRK_DURATION}" --latency "http://127.0.0.1:${PORT}${ENDPOINT}" 2>&1)

RPS=$(echo "$RESULT" | grep "Requests/sec" | awk '{print $2}')
P50=$(echo "$RESULT" | grep "50%" | awk '{print $2}')
P99=$(echo "$RESULT" | grep "99%" | awk '{print $2}')

echo "$NAME|$RPS|$P50|$P99"

# Stop server: kill by port first (catches Gradle child JVM),
# then kill PID as fallback.
kill_port "$PORT"
kill "$PID" 2>/dev/null || true
wait "$PID" 2>/dev/null || true
