#!/usr/bin/env bash
#
# bench-run.sh — wrk benchmark runner for keel HTTP servers
#
# Usage:
#   ./scripts/bench-run.sh                    # Run all engines
#   ./scripts/bench-run.sh --engine=keel      # Run specific engine
#   ./scripts/bench-run.sh --engine=cio --port=9090
#
# Prerequisites: wrk (brew install wrk)

set -euo pipefail

cd "$(dirname "$0")/.."

ENGINES="${1:---engine=all}"
ENGINE_NAME="${ENGINES#--engine=}"
PORT="${2:---port=8080}"
PORT_NUM="${PORT#--port=}"
RESULTS_DIR="benchmark/results"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
WRK_CMD="wrk"

if ! command -v "$WRK_CMD" &>/dev/null; then
    echo "ERROR: wrk not found. Install with: brew install wrk" >&2
    exit 1
fi

mkdir -p "$RESULTS_DIR"

# wrk test parameters
declare -a SCENARIOS=("/hello" "/large")
declare -a PROFILES=("low:2:10:10s" "high:4:100:10s" "ultra:4:500:10s")

ALL_ENGINES=("keel" "keel-netty" "cio" "ktor-netty" "spring" "vertx")

run_benchmark() {
    local engine="$1"
    local scenario="$2"
    local profile_name="$3"
    local threads="$4"
    local connections="$5"
    local duration="$6"
    local endpoint="${scenario##*/}"

    local outfile="${RESULTS_DIR}/${engine}-${endpoint}-${profile_name}-${TIMESTAMP}.txt"

    echo "  wrk -t${threads} -c${connections} -d${duration} --latency http://127.0.0.1:${PORT_NUM}${scenario}"
    $WRK_CMD -t"$threads" -c"$connections" -d"$duration" --latency \
        "http://127.0.0.1:${PORT_NUM}${scenario}" > "$outfile" 2>&1

    # Print summary line
    local rps
    rps=$(grep "Requests/sec" "$outfile" | awk '{print $2}')
    echo "    => ${rps} req/s (saved to $outfile)"
}

run_engine() {
    local engine="$1"

    echo ""
    echo "=== Engine: $engine ==="
    echo "Starting server..."

    # Start server in background
    ./gradlew -Pbenchmark -q :benchmark:run --args="--engine=$engine --port=$PORT_NUM" &
    local server_pid=$!

    # Wait for server to be ready
    local ready=false
    for i in $(seq 1 20); do
        if curl -s -o /dev/null "http://127.0.0.1:${PORT_NUM}/hello" 2>/dev/null; then
            ready=true
            break
        fi
        sleep 0.5
    done

    if [ "$ready" = false ]; then
        echo "ERROR: Server did not start within 10 seconds" >&2
        kill "$server_pid" 2>/dev/null || true
        return 1
    fi

    echo "Server ready. Running benchmarks..."

    for scenario in "${SCENARIOS[@]}"; do
        for profile in "${PROFILES[@]}"; do
            IFS=':' read -r profile_name threads connections duration <<< "$profile"
            run_benchmark "$engine" "$scenario" "$profile_name" "$threads" "$connections" "$duration"
        done
    done

    echo "Stopping server..."
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
    sleep 1
}

echo "=== keel benchmark runner ==="
echo "Timestamp: $TIMESTAMP"
echo "Results:   $RESULTS_DIR/"

if [ "$ENGINE_NAME" = "all" ]; then
    for engine in "${ALL_ENGINES[@]}"; do
        run_engine "$engine"
    done
else
    run_engine "$ENGINE_NAME"
fi

echo ""
echo "=== Done ==="
echo "Run ./scripts/bench-compare.sh to see comparison table"
