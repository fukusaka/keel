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
#   upload        POST /upload-stream  (request-body streaming throughput)
#   sse           GET  /sse-stream     (response-body streaming throughput)
#   ws-echo       GET  /ws-echo        (WebSocket echo throughput, small frames)
#   ws-large      GET  /ws-echo        (single-VU large-frame round-trip throughput
#                                       via ws-large.js, default 1 MB binary
#                                       payload — exercises the server's ability
#                                       to deliver a single message bigger than
#                                       the kernel send buffer)
#   ws-fragment   GET  /ws-echo        (RFC 6455 fragmented-frame send + reassembly
#                                       echo bench via the custom Go client at
#                                       benchmark/wsbench/. k6 cannot construct
#                                       fragmented frames, so this scenario
#                                       requires the wsbench binary to exist —
#                                       build with `cd benchmark/wsbench && go build`)
#
# Environment variables (HTTP-level):
#   BENCH_RUNS                    Number of runs; median is reported (default: 1)
#   BENCH_COOLDOWN                Seconds between runs (default: 2)
#   BENCH_K6_SUCCESS_THRESHOLD    Minimum checks_succeeded percentage to accept
#                                 a run as valid (default: 95). Lower values
#                                 are reported as `checks=NN.NN%` instead of
#                                 a phantom RPS number — protects against
#                                 servers that respond fast but corruptly
#                                 (e.g. a chunked-encoder bug that fails
#                                 99.98% of SSE body-size checks).
#
# Environment variables forwarded to k6 (script-specific defaults apply):
#   BENCH_K6_VUS            k6 virtual users          (default: 50)
#   BENCH_K6_DURATION       k6 bench duration         (default: 15s)
#   BENCH_PAYLOAD_KB        upload.js payload size KB (default: 64)
#   BENCH_UPLOAD_BYTES      upload.js payload size bytes (overrides
#                            BENCH_PAYLOAD_KB if set; accepts MB-scale,
#                            e.g. 10485760 = 10 MB)
#   BENCH_SSE_COUNT         sse.js frame count        (default: 100)
#   BENCH_SSE_SIZE          sse.js per-frame bytes    (default: 1024)
#   BENCH_WS_PAYLOAD        ws-echo.js msg size bytes (default: 256)
#   BENCH_WS_PING_PONGS     msgs per VU before close  (default: unlimited until duration)
#   BENCH_WS_TYPE           ws-echo.js payload type   (text | binary, default: text)
#   BENCH_WS_CLOSE_HANDSHAKE ws-echo.js initiate WS close handshake at end of
#                            session instead of TCP close (true | false,
#                            default: false)
#   BENCH_WS_LARGE_BYTES    ws-large.js single-message payload bytes
#                            (default: 1048576 = 1 MB)
#   BENCH_WS_FRAG_BYTES     ws-fragment.go single-message payload bytes
#                            (default: 4096)
#   BENCH_WS_FRAG_COUNT     ws-fragment.go frame count per message
#                            (default: 4)
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
    ws-large)
        SCRIPT="benchmark/k6/ws-large.js"
        READY_ENDPOINT="/hello"
        PARSER="ws"
        ;;
    ws-fragment)
        # The Go-based wsbench client constructs RFC 6455 fragmented
        # frames (k6's k6/ws cannot). It already emits the
        # `<name>|<rps>|<p50>|<p99>` row format directly so the k6
        # parser path is bypassed.
        SCRIPT="benchmark/wsbench/wsbench"
        READY_ENDPOINT="/hello"
        PARSER="wsbench"
        ;;
    *)
        echo "Unknown scenario: $SCENARIO (expected: upload|sse|ws-echo|ws-large|ws-fragment)" >&2
        exit 1
        ;;
esac

PORT=18090
RUNS=${BENCH_RUNS:-1}
COOLDOWN=${BENCH_COOLDOWN:-2}
READY_TIMEOUT=60
K6_VUS=${BENCH_K6_VUS:-50}
K6_DURATION=${BENCH_K6_DURATION:-15s}

# Save raw k6 output alongside wrk results so summaries can be recreated
# from log evidence rather than re-running everything. Mirrors the
# directory layout used by `bench-keel.sh` / `bench-all.sh`.
RESULTS_BASE="benchmark/results"
HOST_LABEL="${BENCH_HOST_LABEL:-$(hostname -s)}"
RESULTS_DIR="${RESULTS_BASE}/${HOST_LABEL}"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
mkdir -p "$RESULTS_DIR"

# Extract --port=N from args if present
for arg in "$@"; do
    case "$arg" in
        --port=*) PORT="${arg#--port=}" ;;
    esac
done

if [ "$PARSER" = "wsbench" ]; then
    # Custom Go client; require pre-built binary to keep this script
    # ecosystem-free at runtime (matches the rust-hello / go-hello /
    # swift-hello / zig-hello convention).
    if [ ! -x "$SCRIPT" ]; then
        echo "wsbench binary not built (cd benchmark/wsbench && go build)" >&2
        exit 1
    fi
elif ! command -v k6 >/dev/null 2>&1; then
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

extract_metric_pct() {
    local out="$1" metric="$2" pct="$3"
    # awk -v with single-backslash `\(` collapses to a regex group, not a
    # literal paren. Use a bracket expression `[(]` / `[)]` instead so the
    # parens are unambiguously literal regardless of awk -v escaping rules.
    printf '%s' "$out" | awk -v m="$metric" -v p="^p[(]${pct}[)]=" '$0 ~ "^[[:space:]]*"m {
        for (i = 1; i <= NF; i++) if ($i ~ p) { sub(p, "", $i); print $i; exit }
    }'
}

parse_k6_output() {
    local out="$1"
    local kind="$2"
    local rps_metric duration_metric duration_metric_fallback=""
    case "$kind" in
        ws)
            # WebSocket bench: count echoed messages received/sec.
            # Latency: prefer k6's built-in `ws_ping` Trend (Go-side ns
            # precision, populated by `socket.ping()` in ws-echo.js)
            # when present; ws-large.js doesn't ping (it's measuring
            # large-message round-trip, not control-frame RTT) so the
            # parser falls back to the JS-side `ws_msg_rtt_ms` Trend
            # which is fine for >1 ms RTTs.
            rps_metric="ws_msgs_received"
            duration_metric="ws_ping"
            duration_metric_fallback="ws_msg_rtt_ms"
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
    p50=$(extract_metric_pct "$out" "$duration_metric" "50")
    p99=$(extract_metric_pct "$out" "$duration_metric" "99")
    if [ -z "$p50" ] && [ -n "$duration_metric_fallback" ]; then
        p50=$(extract_metric_pct "$out" "$duration_metric_fallback" "50")
        p99=$(extract_metric_pct "$out" "$duration_metric_fallback" "99")
    fi
    printf '%s|%s|%s\n' "$rps" "$p50" "$p99"
}

# Extract the success rate from k6's checks output. k6 emits two formats
# depending on version:
#   v1.x:  `checks_succeeded...: 99.97% 1234567 out of 1234999`
#          (paired with a separate `checks_failed` line)
#   v0.x:  `checks.....................: 99.97%   1234567 out of 1234999`
#          (single combined line)
# Both expose the success percentage as the first %-token on the line, so
# we match `checks` or `checks_succeeded` and grab the first %-suffixed
# field. Used to flag corrupt benchmarks (e.g. SSE bodies that fail body-
# size validation under HTTP keep-alive bugs) so the harness can mark
# them FAILED instead of reporting throughput numbers built on failed
# responses.
extract_success_rate() {
    local out="$1"
    printf '%s' "$out" | awk '/^[[:space:]]*checks(_succeeded)?[[:space:].]*:/ {
        for (i = 1; i <= NF; i++) if ($i ~ /%$/) { sub(/%$/, "", $i); print $i; exit }
    }'
}

# --- Run ---

ALL_RPS=()
BEST_RPS=0
BEST_P50=""
BEST_P99=""

for run in $(seq 1 "$RUNS"); do
    kill_port "$PORT"
    sleep 1

    # See bench-one.sh for the rationale: setsid lets us kill the entire
    # process group so JVM helper threads / native forks don't leak.
    USED_SETSID=false
    if command -v setsid >/dev/null 2>&1; then
        setsid "$@" >/dev/null 2>&1 &
        USED_SETSID=true
    else
        "$@" >/dev/null 2>&1 &
    fi
    PID=$!

    kill_server() {
        if [ "$USED_SETSID" = true ]; then
            kill -TERM -- "-$PID" 2>/dev/null || true
        else
            kill "$PID" 2>/dev/null || true
        fi
    }

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
        kill_server
        wait "$PID" 2>/dev/null || true
        exit 1
    fi

    SAFE_NAME=$(printf '%s' "$NAME" | tr -c 'A-Za-z0-9._-' '-')
    RAW_FILE="${RESULTS_DIR}/${SAFE_NAME}-${SCENARIO}-${K6_VUS}vu-${K6_DURATION}-${TIMESTAMP}-run${run}.txt"

    if [ "$PARSER" = "wsbench" ]; then
        # Custom Go client. Already emits the canonical
        # `<name>|<rps>|<p50>|<p99>` row, so no parsing needed —
        # capture stdout straight as the bench output.
        K6_OUT=$(
            "$SCRIPT" \
                -name="$NAME" \
                -scenario=fragment-recv \
                -host=127.0.0.1 \
                -port="$PORT" \
                -vus="$K6_VUS" \
                -duration="$K6_DURATION" \
                -bytes="${BENCH_WS_FRAG_BYTES:-4096}" \
                -fragments="${BENCH_WS_FRAG_COUNT:-4}" \
                2>&1
        )
        printf '%s\n' "$K6_OUT" > "$RAW_FILE"
        # The wsbench output line is already in the right shape; pull
        # only the line starting with the engine name.
        ROW=$(printf '%s' "$K6_OUT" | grep -E "^${SAFE_NAME}\|" | tail -1)
        if [ -z "$ROW" ]; then
            PARSED="||"
        else
            PARSED=$(printf '%s' "$ROW" | cut -d'|' -f2-)
        fi
    else
        # k6 with p50/p99 enabled in the summary trend stats.
        K6_OUT=$(
            HOST=127.0.0.1 PORT="$PORT" \
            VUS="$K6_VUS" DURATION="$K6_DURATION" \
            PAYLOAD_KB="${BENCH_PAYLOAD_KB:-64}" \
            UPLOAD_BYTES="${BENCH_UPLOAD_BYTES:-0}" \
            COUNT="${BENCH_SSE_COUNT:-100}" SIZE="${BENCH_SSE_SIZE:-1024}" \
            PAYLOAD_BYTES="${BENCH_WS_PAYLOAD:-256}" \
            PAYLOAD_TYPE="${BENCH_WS_TYPE:-text}" \
            CLOSE_HANDSHAKE="${BENCH_WS_CLOSE_HANDSHAKE:-false}" \
            WS_LARGE_BYTES="${BENCH_WS_LARGE_BYTES:-1048576}" \
            PING_PONGS="${BENCH_WS_PING_PONGS:-0}" \
            k6 run --quiet --no-color \
                --summary-trend-stats="avg,min,med,max,p(50),p(95),p(99)" \
                "$SCRIPT" 2>&1
        )
        printf '%s\n' "$K6_OUT" > "$RAW_FILE"
        PARSED=$(parse_k6_output "$K6_OUT" "$PARSER")
    fi
    RPS=$(echo "$PARSED" | cut -d'|' -f1)
    P50=$(echo "$PARSED" | cut -d'|' -f2)
    P99=$(echo "$PARSED" | cut -d'|' -f3)

    # Validate success rate. k6's `http_reqs` / `ws_msgs_received` count
    # everything including failed responses, so a server that returns
    # 99% errors at 50K/s would otherwise look like "50K RPS". If checks
    # are present and below the threshold, treat the run as failed and
    # surface the failure ratio in the latency columns so it isn't
    # silently dropped from the summary table.
    SUCCESS_RATE=$(extract_success_rate "$K6_OUT")
    THRESHOLD="${BENCH_K6_SUCCESS_THRESHOLD:-95}"
    INVALID=false
    if [ -n "$SUCCESS_RATE" ] && awk "BEGIN {exit !($SUCCESS_RATE < $THRESHOLD)}" 2>/dev/null; then
        INVALID=true
        RPS=""
        P50="checks=${SUCCESS_RATE}%"
        P99="-"
    fi

    ALL_RPS+=("$RPS")

    if [ "$INVALID" = true ]; then
        # Failure marker wins over any prior run's RPS so the operator
        # sees the corruption at a glance.
        BEST_P50="$P50"
        BEST_P99="$P99"
    elif [ -n "$RPS" ] && awk "BEGIN {exit !($RPS > $BEST_RPS)}" 2>/dev/null; then
        BEST_RPS="$RPS"
        BEST_P50="$P50"
        BEST_P99="$P99"
    fi

    kill_port "$PORT"
    kill_server
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
