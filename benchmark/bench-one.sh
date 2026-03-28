#!/usr/bin/env bash
# Benchmark a single server
#
# Usage: ./benchmark/bench-one.sh <name> <command> [args...]
#
# Environment variables:
#   BENCH_ENDPOINT       Endpoint to benchmark (default: /hello)
#   BENCH_RUNS           Number of runs; median is reported (default: 1)
#   BENCH_COOLDOWN       Seconds between runs (default: 2)
#   BENCH_WRK_THREADS    wrk threads (default: 4)
#   BENCH_WRK_CONNS      wrk connections (default: 100)
#   BENCH_WRK_DURATION   wrk duration (default: 10s)
#
# Example:
#   ./benchmark/bench-one.sh rust-hello benchmark/rust-hello/target/release/rust-hello --port=18090
#   BENCH_ENDPOINT=/large BENCH_RUNS=3 ./benchmark/bench-one.sh ktor-keel-epoll ./benchmark.kexe --engine=ktor-keel-epoll --port=18090

set -uo pipefail

NAME="${1:?Usage: bench-one.sh <name> <command> [args...]}"
shift

PORT=18090
WRK_THREADS=${BENCH_WRK_THREADS:-4}
WRK_CONNS=${BENCH_WRK_CONNS:-100}
WRK_DURATION=${BENCH_WRK_DURATION:-10s}
ENDPOINT="${BENCH_ENDPOINT:-/hello}"
RUNS=${BENCH_RUNS:-1}
COOLDOWN=${BENCH_COOLDOWN:-2}
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

# --- Compute median of space-separated numbers ---

median() {
    echo "$@" | tr ' ' '\n' | sort -n | awk '{a[NR]=$1} END {
        if (NR%2==1) print a[(NR+1)/2]
        else printf "%.2f\n", (a[NR/2]+a[NR/2+1])/2
    }'
}

# --- Run benchmark ---

ALL_RPS=()
BEST_RPS=0
BEST_P50=""
BEST_P99=""

for run in $(seq 1 "$RUNS"); do
    # Kill any existing process on the port
    kill_port "$PORT"
    sleep 1

    # Start server
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
    ALL_RPS+=("$RPS")

    if [ -n "$RPS" ] && awk "BEGIN {exit !($RPS > $BEST_RPS)}" 2>/dev/null; then
        BEST_RPS="$RPS"
        BEST_P50="$P50"
        BEST_P99="$P99"
    fi

    # Stop server
    kill_port "$PORT"
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true

    # Cooldown between runs
    if [ "$run" -lt "$RUNS" ]; then
        sleep "$COOLDOWN"
    fi
done

# Output
if [ "$RUNS" -gt 1 ]; then
    MEDIAN_RPS=$(median "${ALL_RPS[@]}")
    echo "$NAME|$MEDIAN_RPS|$BEST_P50|$BEST_P99|[${ALL_RPS[*]}]"
else
    echo "$NAME|${ALL_RPS[0]}|$BEST_P50|$BEST_P99"
fi
