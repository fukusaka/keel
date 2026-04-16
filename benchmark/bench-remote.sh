#!/usr/bin/env bash
# Benchmark a keel server running on a remote host, with wrk driven from a
# separate client host over a real network link. Complements bench-one.sh
# (loopback) with a real-NIC measurement path.
#
# Usage: ./benchmark/bench-remote.sh <name> <command> [args...]
#
# Required environment variables:
#   BENCH_REMOTE_HOST    Server host (ssh target that runs <command>)
#   BENCH_CLIENT_HOST    wrk client host (ssh target that drives the load)
#
# Optional environment variables:
#   BENCH_REMOTE_WORKDIR Path to the keel checkout on the server host
#                        (default: ~/prj/keel-work/keel)
#   BENCH_SERVER_IP      IP or hostname the client uses in the wrk URL; some
#                        client hosts cannot resolve mDNS hostnames, so a
#                        routable IP is required (default: BENCH_REMOTE_HOST)
#   BENCH_WRK_MODE       wrk invocation mode on the client: "native" (use
#                        /usr/bin/wrk), "docker" (run a wrk container with
#                        --network=host via sudo -n docker), or "auto"
#                        (default: auto — probe native first, then docker)
#   BENCH_WRK_DOCKER_IMAGE
#                        Docker image used in "docker" mode
#                        (default: williamyeh/wrk:latest)
#
#   Plus the standard tuning knobs, identical in meaning to bench-one.sh:
#     BENCH_ENDPOINT     (default: /hello)
#     BENCH_RUNS         (default: 1; median reported when >1)
#     BENCH_COOLDOWN     (default: 2 s between runs)
#     BENCH_WRK_THREADS  (default: 4)
#     BENCH_WRK_CONNS    (default: 100)
#     BENCH_WRK_DURATION (default: 10s)
#     BENCH_WARMUP       (default: 3s)
#     BENCH_PORT         (default: 18090)
#     BENCH_SCHEME       (default: http; https requires the engine to
#                         serve TLS — no cert validation on the client)
#
# Output format (matches bench-one.sh):
#   Single run : <name>|<rps>|<p50>|<p99>
#   Multi-run  : <name>|<median_rps>|<p50>|<p99>|[<all_rps>]
#
# Example:
#   BENCH_REMOTE_HOST=bench.example \
#     BENCH_CLIENT_HOST=client.example \
#     BENCH_SERVER_IP=10.0.0.10 \
#     BENCH_RUNS=3 \
#     ./benchmark/bench-remote.sh pipeline-http-io-uring \
#       benchmark/build/bin/linuxX64/releaseExecutable/benchmark.kexe \
#       --engine=pipeline-http-io-uring --port=18090

set -uo pipefail

NAME="${1:?Usage: bench-remote.sh <name> <command> [args...]}"
shift
if [ "$#" -lt 1 ]; then
    echo "Usage: bench-remote.sh <name> <command> [args...]" >&2
    exit 1
fi

: "${BENCH_REMOTE_HOST:?BENCH_REMOTE_HOST is required (server host for ssh)}"
: "${BENCH_CLIENT_HOST:?BENCH_CLIENT_HOST is required (wrk client host for ssh)}"

REMOTE_HOST="$BENCH_REMOTE_HOST"
CLIENT_HOST="$BENCH_CLIENT_HOST"
WORKDIR="${BENCH_REMOTE_WORKDIR:-~/prj/keel-work/keel}"
SERVER_IP="${BENCH_SERVER_IP:-$REMOTE_HOST}"
WRK_MODE="${BENCH_WRK_MODE:-auto}"
WRK_DOCKER_IMAGE="${BENCH_WRK_DOCKER_IMAGE:-williamyeh/wrk:latest}"

PORT=${BENCH_PORT:-18090}
WRK_THREADS=${BENCH_WRK_THREADS:-4}
WRK_CONNS=${BENCH_WRK_CONNS:-100}
WRK_DURATION=${BENCH_WRK_DURATION:-10s}
ENDPOINT="${BENCH_ENDPOINT:-/hello}"
RUNS=${BENCH_RUNS:-1}
COOLDOWN=${BENCH_COOLDOWN:-2}
WARMUP_DURATION=${BENCH_WARMUP:-3s}
SCHEME=${BENCH_SCHEME:-http}
READY_TIMEOUT=60

# Allow --port=N to override BENCH_PORT.
for arg in "$@"; do
    case "$arg" in
        --port=*) PORT="${arg#--port=}" ;;
    esac
done

URL="${SCHEME}://${SERVER_IP}:${PORT}${ENDPOINT}"
LOG_PATH="/tmp/bench-remote-${NAME}.log"

# --- Resolve wrk invocation on the client ---

probe_wrk_native() {
    ssh -n "$CLIENT_HOST" 'command -v wrk >/dev/null 2>&1'
}
probe_wrk_docker() {
    ssh -n "$CLIENT_HOST" 'sudo -n docker --version >/dev/null 2>&1'
}

if [ "$WRK_MODE" = auto ]; then
    if probe_wrk_native; then
        WRK_MODE=native
    elif probe_wrk_docker; then
        WRK_MODE=docker
    else
        echo "ERROR: no wrk on ${CLIENT_HOST}. Install wrk or enable passwordless sudo docker," >&2
        echo "       then retry, or set BENCH_WRK_MODE=native|docker explicitly." >&2
        exit 1
    fi
fi

run_wrk() {
    # Args: wrk CLI tokens — `printf %q` escapes each one so URL shell
    # metacharacters (e.g. `&` in a query string) survive the remote
    # shell re-parse. Without this, `http://host/path?a=1&b=2` is
    # truncated to `http://host/path?a=1` on the remote side (the `&`
    # backgrounds the rest).
    local quoted
    printf -v quoted '%q ' "$@"
    case "$WRK_MODE" in
        native)
            ssh -n "$CLIENT_HOST" "wrk ${quoted}"
            ;;
        docker)
            ssh -n "$CLIENT_HOST" "sudo -n docker run --rm --network=host ${WRK_DOCKER_IMAGE} ${quoted}"
            ;;
        *)
            echo "ERROR: unknown BENCH_WRK_MODE=${WRK_MODE} (expected native|docker|auto)" >&2
            exit 1
            ;;
    esac
}

# --- Server lifecycle on the remote host ---

kill_server() {
    # Kill by port binding. `fuser` is widely available on Linux server hosts.
    # Redirect stdout too — `fuser -k` prints matched PIDs on stdout, and
    # leaking them into the script's stdout would corrupt the parsed
    # benchmark output line.
    ssh -n "$REMOTE_HOST" "fuser -k ${PORT}/tcp >/dev/null 2>&1 || true"
    sleep 1
}

start_server() {
    # Detach pattern: `cd && { nohup ... & disown; }`. The brace group
    # scopes `&` to the single nohup command — the whole compound is
    # NOT backgrounded, so no extra subshell inherits ssh's stdio fds
    # and ssh returns as soon as the outer bash finishes the brace
    # group. Writing this as `cd && nohup ... & disown` (no braces)
    # parses as `(cd && nohup ...) & disown`, which forks a subshell
    # holding the ssh fds and blocks ssh until the child process exits.
    # `cd; nohup ... & disown` would also work, but braces are more
    # resistant to a future edit that "fixes" the `;` to `&&`.
    local quoted_cmd
    printf -v quoted_cmd '%q ' "$@"
    # BENCH_SERVER_ENV: space-separated `KEY=VALUE` pairs to prefix on the
    # remote command, e.g. `BENCH_SERVER_ENV="BENCH_ACCEPT_DIRECT_ALLOC=true"`.
    # Used to flip opt-in io_uring capabilities for A/B runs without editing
    # the benchmark binary. Empty when unset.
    local server_env="${BENCH_SERVER_ENV:-}"
    ssh -n "$REMOTE_HOST" "cd ${WORKDIR} && { nohup env ${server_env} ${quoted_cmd}>${LOG_PATH} 2>&1 </dev/null & disown; }"
}

wait_for_ready() {
    local i
    for i in $(seq 1 "$READY_TIMEOUT"); do
        if ssh -n "$REMOTE_HOST" "ss -lnt | grep -q ':${PORT}\b'"; then
            return 0
        fi
        sleep 1
    done
    return 1
}

# --- Median helper (same as bench-one.sh) ---

median() {
    echo "$@" | tr ' ' '\n' | sort -n | awk '{a[NR]=$1} END {
        if (NR%2==1) print a[(NR+1)/2]
        else printf "%.2f\n", (a[NR/2]+a[NR/2+1])/2
    }'
}

# --- Main loop ---

ALL_RPS=()
BEST_RPS=0
BEST_P50=""
BEST_P99=""

for run in $(seq 1 "$RUNS"); do
    kill_server
    start_server "$@"

    if ! wait_for_ready; then
        echo "$NAME|FAILED|-|-" >&2
        ssh -n "$REMOTE_HOST" "tail -5 ${LOG_PATH}" >&2 || true
        kill_server
        exit 1
    fi

    # BENCH_WRK_EXTRA: extra wrk CLI tokens, word-split with no escaping
    # (caller is trusted). Example: `BENCH_WRK_EXTRA='-H Connection:close'`.
    # Use word-splitting on an unquoted expansion rather than the
    # `printf %q` path because wrk flags like `-H <header>` span two
    # tokens that must split apart.
    WRK_EXTRA_ARR=()
    if [ -n "${BENCH_WRK_EXTRA:-}" ]; then
        # shellcheck disable=SC2206 # intentional word-split of caller-provided string
        WRK_EXTRA_ARR=(${BENCH_WRK_EXTRA})
    fi

    # Warmup
    run_wrk -t2 -c10 "-d${WARMUP_DURATION}" "${WRK_EXTRA_ARR[@]}" "${URL}" >/dev/null 2>&1 || true

    # Benchmark
    RESULT=$(run_wrk "-t${WRK_THREADS}" "-c${WRK_CONNS}" "-d${WRK_DURATION}" --latency "${WRK_EXTRA_ARR[@]}" "${URL}" 2>&1)

    RPS=$(echo "$RESULT" | awk '/Requests\/sec/ {print $2}')
    P50=$(echo "$RESULT" | awk '/^[[:space:]]*50%/ {print $2}')
    P99=$(echo "$RESULT" | awk '/^[[:space:]]*99%/ {print $2}')
    ALL_RPS+=("$RPS")

    if [ -n "$RPS" ] && awk "BEGIN {exit !($RPS > $BEST_RPS)}" 2>/dev/null; then
        BEST_RPS="$RPS"
        BEST_P50="$P50"
        BEST_P99="$P99"
    fi

    kill_server

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
