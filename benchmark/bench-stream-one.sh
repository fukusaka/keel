#!/usr/bin/env bash
# Benchmark a single server with k6 (streaming endpoints — /upload-stream / /sse-stream)
#
# Mirrors bench-one.sh's contract (start server, run client, parse output,
# emit `<name>|<rps>|<p50>|<p99>` row) but drives k6 instead of wrk so the
# request-body / response-body streaming paths can be exercised.
#
# Usage: ./benchmark/bench-stream-one.sh <name> <scenario> <command> [args...]
#
# scenario:
#   upload   POST /upload-stream  (request-body streaming throughput)
#   sse      GET  /sse-stream     (response-body streaming throughput)
#   ws-echo  GET  /ws-echo        (WebSocket echo throughput)
#
# Environment variables (HTTP-level):
#   BENCH_RUNS           Number of runs; median is reported (default: 1)
#   BENCH_COOLDOWN       Seconds between runs (default: 2)
#
# Environment variables forwarded to k6 (script-specific defaults apply):
#   BENCH_K6_VUS         k6 virtual users          (default: 50)
#   BENCH_K6_DURATION    k6 bench duration         (default: 15s)
#   BENCH_PAYLOAD_KB     upload.js payload size KB (default: 64)
#   BENCH_SSE_COUNT      sse.js frame count        (default: 100)
#   BENCH_SSE_SIZE       sse.js per-frame bytes    (default: 1024)
#   BENCH_WS_PAYLOAD     ws-echo.js msg size bytes (default: 256)
#   BENCH_WS_PING_PONGS  msgs per VU before close  (default: unlimited until duration)
#
# Example:
#   ./benchmark/bench-stream-one.sh ktor-keel-nio upload \
#       benchmark/build/bin/.../benchmark.kexe --engine=ktor-keel-nio --port=18090
#   BENCH_PAYLOAD_KB=256 ./benchmark/bench-stream-one.sh ktor-cio upload \
#       java -cp ... io.github.fukusaka.keel.benchmark.JvmMainKt --engine=ktor-cio --port=18090

set -uo pipefail

NAME="${1:?Usage: bench-stream-one.sh <name> <scenario> <command> [args...]}"
SCENARIO="${2:?Usage: bench-stream-one.sh <name> <scenario> <command> [args...]}"
shift 2

# Map scenario name to k6 script + endpoint hint (for readiness probe) +
# parser kind (HTTP req metrics vs WebSocket session/msg metrics).
case "$SCENARIO" in
    upload)
        SCRIPT="benchmark/k6/upload.js"
        READY_ENDPOINT="/hello"
        PARSER="http"
        ;;
    sse)
        SCRIPT="benchmark/k6/sse.js"
        READY_ENDPOINT="/hello"
        PARSER="http"
        ;;
    ws-echo)
        SCRIPT="benchmark/k6/ws-echo.js"
        READY_ENDPOINT="/hello"
        PARSER="ws"
        ;;
    *)
        echo "Unknown scenario: $SCENARIO (expected: upload|sse|ws-echo)" >&2
        exit 1
        ;;
esac

PORT=18090
RUNS=${BENCH_RUNS:-1}
COOLDOWN=${BENCH_COOLDOWN:-2}
READY_TIMEOUT=60
K6_VUS=${BENCH_K6_VUS:-50}
K6_DURATION=${BENCH_K6_DURATION:-15s}

# Extract --port=N from args if present
for arg in "$@"; do
    case "$arg" in
        --port=*) PORT="${arg#--port=}" ;;
    esac
done

if ! command -v k6 >/dev/null 2>&1; then
    echo "k6 not installed (see benchmark/k6/README.md)" >&2
    exit 1
fi

# --- Port management (reused from bench-one.sh) ---

kill_port() {
    local port="$1"
    local pids
    if [ "$(uname)" = "Linux" ] && command -v fuser >/dev/null 2>&1; then
        pids=$(fuser "$port"/tcp 2>/dev/null) || return 0
    elif command -v lsof >/dev/null 2>&1; then
        pids=$(lsof -ti :"$port" 2>/dev/null) || return 0
    else
        return 0
    fi
    [ -z "$pids" ] && return 0
    kill $pids 2>/dev/null || return 0
    for _ in $(seq 1 20); do
        kill -0 $pids 2>/dev/null || return 0
        sleep 0.1
    done
    kill -9 $pids 2>/dev/null || true
}

median() {
    echo "$@" | tr ' ' '\n' | sort -n | awk '{a[NR]=$1} END {
        if (NR%2==1) print a[(NR+1)/2]
        else printf "%.2f\n", (a[NR/2]+a[NR/2+1])/2
    }'
}

# --- k6 output parser ---
#
# k6's text summary contains lines like:
#   http_reqs..........: 12345    823.45/s
#   http_req_duration..: avg=1.21ms ... p(50)=1.0ms p(95)=2.5ms p(99)=4.2ms
# We extract: rps from http_reqs, p50 / p99 from http_req_duration.
# Latency strings keep the unit suffix (ms / us) so consumers can format.

parse_k6_output() {
    local out="$1"
    local kind="$2"
    local rps_metric duration_metric
    case "$kind" in
        ws)
            # WebSocket bench: count echoed messages received/sec; latency
            # comes from the per-frame round-trip metric ws_session_duration
            # captures the connect + first message hop, but ws_msg_received
            # rate is the throughput signal we want.
            rps_metric="ws_msgs_received"
            duration_metric="ws_session_duration"
            ;;
        *)
            rps_metric="http_reqs"
            duration_metric="http_req_duration"
            ;;
    esac
    local rps p50 p99
    rps=$(printf '%s' "$out" | awk -v m="$rps_metric" '$0 ~ "^[[:space:]]*"m {
        for (i = NF; i > 0; i--) if ($i ~ /\/s$/) { sub(/\/s$/, "", $i); print $i; exit }
    }')
    p50=$(printf '%s' "$out" | awk -v m="$duration_metric" '$0 ~ "^[[:space:]]*"m {
        for (i = 1; i <= NF; i++) if ($i ~ /^p\(50\)=/) { sub(/^p\(50\)=/, "", $i); print $i; exit }
    }')
    p99=$(printf '%s' "$out" | awk -v m="$duration_metric" '$0 ~ "^[[:space:]]*"m {
        for (i = 1; i <= NF; i++) if ($i ~ /^p\(99\)=/) { sub(/^p\(99\)=/, "", $i); print $i; exit }
    }')
    printf '%s|%s|%s\n' "$rps" "$p50" "$p99"
}

# --- Run ---

ALL_RPS=()
BEST_RPS=0
BEST_P50=""
BEST_P99=""

for run in $(seq 1 "$RUNS"); do
    kill_port "$PORT"
    sleep 1

    if command -v setsid >/dev/null 2>&1; then
        setsid "$@" >/dev/null 2>&1 &
    else
        "$@" >/dev/null 2>&1 &
    fi
    PID=$!

    # Validate HTTP status, not just TCP connect (5xx would otherwise pass).
    READY=false
    for _ in $(seq 1 "$READY_TIMEOUT"); do
        STATUS=$(curl -sk -o /dev/null -w '%{http_code}' \
            "http://127.0.0.1:${PORT}${READY_ENDPOINT}" 2>/dev/null) || STATUS=000
        case "$STATUS" in
            2??|3??) READY=true; break ;;
        esac
        sleep 0.5
    done

    if [ "$READY" = false ]; then
        echo "$NAME|FAILED|-|-"
        kill_port "$PORT"
        kill "$PID" 2>/dev/null || true
        wait "$PID" 2>/dev/null || true
        exit 1
    fi

    # k6 with p50/p99 enabled in the summary trend stats.
    K6_OUT=$(
        HOST=127.0.0.1 PORT="$PORT" \
        VUS="$K6_VUS" DURATION="$K6_DURATION" \
        PAYLOAD_KB="${BENCH_PAYLOAD_KB:-64}" \
        COUNT="${BENCH_SSE_COUNT:-100}" SIZE="${BENCH_SSE_SIZE:-1024}" \
        PAYLOAD_BYTES="${BENCH_WS_PAYLOAD:-256}" \
        PING_PONGS="${BENCH_WS_PING_PONGS:-0}" \
        k6 run --quiet --no-color \
            --summary-trend-stats="avg,min,med,max,p(50),p(95),p(99)" \
            "$SCRIPT" 2>&1
    )
    PARSED=$(parse_k6_output "$K6_OUT" "$PARSER")
    RPS=$(echo "$PARSED" | cut -d'|' -f1)
    P50=$(echo "$PARSED" | cut -d'|' -f2)
    P99=$(echo "$PARSED" | cut -d'|' -f3)
    ALL_RPS+=("$RPS")

    if [ -n "$RPS" ] && awk "BEGIN {exit !($RPS > $BEST_RPS)}" 2>/dev/null; then
        BEST_RPS="$RPS"
        BEST_P50="$P50"
        BEST_P99="$P99"
    fi

    kill_port "$PORT"
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true

    if [ "$run" -lt "$RUNS" ]; then
        sleep "$COOLDOWN"
    fi
done

if [ "$RUNS" -gt 1 ]; then
    MEDIAN_RPS=$(median "${ALL_RPS[@]}")
    echo "$NAME|$MEDIAN_RPS|$BEST_P50|$BEST_P99|[${ALL_RPS[*]}]"
else
    echo "$NAME|${ALL_RPS[0]}|$BEST_P50|$BEST_P99"
fi
