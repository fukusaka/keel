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
#   GC pressure capture (JVM engines only, no-op on Native servers):
#     BENCH_GC_CAPTURE   (default: 0). When set to 1, jstat samples the
#                        server JVM before and after the wrk benchmark
#                        and the output line carries the deltas
#                        (allocation rate / GC count / GC time fraction).
#                        Raw jstat pre/post samples are also written to
#                        `benchmark/results/{host}/{name}-gc-{ts}.txt`
#                        so a future analyst can recompute metrics with
#                        a more exact formula than the
#                        `YGC × Eden / duration` proxy emitted on the
#                        summary line (the GC count / time deltas
#                        themselves are exact and need no recomputation).
#                        Requires `jstat` on the remote host PATH; Native
#                        servers silently skip (no JVM to attach to).
#
# Output format:
#   Default            : <name>|<rps>|<p50>|<p99>
#   Default + multi-run: <name>|<median_rps>|<p50>|<p99>|[<all_rps>]
#   With GC capture    : <name>|<rps>|<p50>|<p99>|GC:<alloc_MB/s>|<ygc>|<ygc_ms>|<fgc>|<fgc_ms>|<gc_pct>
#   GC + multi-run     : <name>|<median_rps>|<p50>|<p99>|[<all_rps>]|GC:<alloc_MB/s>|<ygc>|<ygc_ms>|<fgc>|<fgc_ms>|<gc_pct>
#   (GC counters report the last run's deltas; multi-run medians of the
#    GC counters are not computed because the sample size is small.)
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
# Probe the server host OS once so the port-management commands pick the right
# tool: Linux has `fuser` / `ss`, macOS has neither (use `lsof`). Override via
# BENCH_REMOTE_OS to skip the probe ssh round-trip. Defaults to Linux when the
# probe fails so existing Linux-server runs are unaffected.
REMOTE_OS="${BENCH_REMOTE_OS:-$(ssh -n "$REMOTE_HOST" uname 2>/dev/null || echo Linux)}"
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
GC_CAPTURE=${BENCH_GC_CAPTURE:-0}
READY_TIMEOUT=${BENCH_READY_TIMEOUT:-60}

# Raw jstat samples land alongside other bench artifacts so
# `bench-snapshot.sh` can pick them up. `bench-keel.sh` writes results
# under benchmark/results/{hostname}/...; bench-remote.sh historically
# didn't, but the GC capture additions need a place for raw artifacts
# that supersedes the inline summary line.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS_DIR="${BENCH_RESULTS_DIR:-${SCRIPT_DIR}/results/${BENCH_REMOTE_HOST%%.*}}"
mkdir -p "$RESULTS_DIR"
GC_TIMESTAMP="$(date +%Y%m%d-%H%M%S)"

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
    # Kill whatever holds the bench port. Redirect stdout — both `fuser -k`
    # and `lsof -t` print PIDs there, and leaking them into the script's
    # stdout would corrupt the parsed benchmark output line.
    if [ "$REMOTE_OS" = "Darwin" ]; then
        # macOS server: no `fuser` — resolve the listener PID via `lsof`.
        ssh -n "$REMOTE_HOST" "lsof -ti tcp:${PORT} 2>/dev/null | xargs kill 2>/dev/null || true"
    else
        ssh -n "$REMOTE_HOST" "fuser -k ${PORT}/tcp >/dev/null 2>&1 || true"
    fi
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
    # Listener probe differs by OS: Linux `ss`, macOS `lsof` (no `ss`).
    local listen_check
    if [ "$REMOTE_OS" = "Darwin" ]; then
        listen_check="lsof -nP -iTCP:${PORT} -sTCP:LISTEN >/dev/null 2>&1"
    else
        listen_check="ss -lnt | grep -q ':${PORT}\b'"
    fi
    for _ in $(seq 1 "$READY_TIMEOUT"); do
        if ssh -n "$REMOTE_HOST" "$listen_check"; then
            return 0
        fi
        sleep 1
    done
    return 1
}

# --- GC capture (JVM engines only) ---
#
# Strategy: take one `jstat -gc <pid>` sample before wrk and one after,
# compute deltas:
#   - YGC / YGCT (young gen GC count / cumulative ms)
#   - FGC / FGCT (full GC count / cumulative ms)
#   - allocation rate proxy = young GC count × Eden capacity / wrk duration
#     (each young GC empties Eden, so promoted ≈ Eden capacity per cycle)
#   - GC fraction = (delta YGCT + delta FGCT) / wrk duration in ms
#
# `jstat -gc <pid>` output columns (HotSpot 21):
#   S0C S1C S0U S1U EC EU OC OU MC MU CCSC CCSU YGC YGCT FGC FGCT CGC CGCT GCT
# All capacities/usages in KB; times in seconds.

# Find the server JVM PID on the remote host. Returns empty string if no
# Java process is bound to PORT (Native server).
find_server_pid() {
    ssh -n "$REMOTE_HOST" "ss -lntp | awk -v p=':${PORT}\$' '\$4 ~ p {print \$NF}' | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2" 2>/dev/null || true
}

is_jvm_pid() {
    local pid="$1"
    [ -n "$pid" ] || return 1
    ssh -n "$REMOTE_HOST" "test -e /proc/${pid}/exe && readlink /proc/${pid}/exe 2>/dev/null | grep -qE '/java\$'" 2>/dev/null
}

# `jstat -gc <pid>` once; output: tab-separated header on stdout line 1,
# data on line 2.
jstat_sample() {
    local pid="$1"
    ssh -n "$REMOTE_HOST" "jstat -gc ${pid} 2>/dev/null" 2>/dev/null || true
}

# Parse a jstat -gc line into the columns we care about. Args:
#   $1 = data line (whitespace-separated)
#   $2 = column index (1-based per the jstat header)
jstat_col() {
    local line="$1" idx="$2"
    echo "$line" | awk -v i="$idx" '{print $i}'
}

# Compute the GC summary string for the just-finished wrk run.
# Args: $1 = pre jstat sample (2 lines), $2 = post jstat sample (2 lines),
#       $3 = wrk duration in seconds
gc_summary() {
    local pre_sample="$1" post_sample="$2" duration_s="$3"
    local pre_data post_data
    pre_data=$(echo "$pre_sample" | sed -n '2p')
    post_data=$(echo "$post_sample" | sed -n '2p')
    if [ -z "$pre_data" ] || [ -z "$post_data" ]; then
        echo ""
        return
    fi
    # Column layout (HotSpot 21 `jstat -gc`):
    #  1 S0C  2 S1C  3 S0U  4 S1U  5 EC  6 EU  7 OC  8 OU  9 MC 10 MU
    # 11 CCSC 12 CCSU 13 YGC 14 YGCT 15 FGC 16 FGCT 17 CGC 18 CGCT 19 GCT
    local ec_post ygc_pre ygc_post ygct_pre ygct_post fgc_pre fgc_post fgct_pre fgct_post
    ec_post=$(jstat_col "$post_data" 5)
    ygc_pre=$(jstat_col "$pre_data" 13)
    ygc_post=$(jstat_col "$post_data" 13)
    ygct_pre=$(jstat_col "$pre_data" 14)
    ygct_post=$(jstat_col "$post_data" 14)
    fgc_pre=$(jstat_col "$pre_data" 15)
    fgc_post=$(jstat_col "$post_data" 15)
    fgct_pre=$(jstat_col "$pre_data" 16)
    fgct_post=$(jstat_col "$post_data" 16)
    # awk handles float deltas; outputs blank if any input is non-numeric.
    awk -v ec="$ec_post" \
        -v ygc_pre="$ygc_pre" -v ygc_post="$ygc_post" \
        -v ygct_pre="$ygct_pre" -v ygct_post="$ygct_post" \
        -v fgc_pre="$fgc_pre" -v fgc_post="$fgc_post" \
        -v fgct_pre="$fgct_pre" -v fgct_post="$fgct_post" \
        -v dur="$duration_s" \
        'BEGIN {
            d_ygc  = ygc_post  - ygc_pre
            d_ygct = ygct_post - ygct_pre   # seconds
            d_fgc  = fgc_post  - fgc_pre
            d_fgct = fgct_post - fgct_pre
            # allocation throughput proxy: each YGC empties Eden of size ec (KB)
            alloc_mb = (d_ygc * ec) / 1024.0
            alloc_mb_per_s = (dur > 0) ? alloc_mb / dur : 0
            gc_ms = (d_ygct + d_fgct) * 1000.0
            gc_pct = (dur > 0) ? 100.0 * (d_ygct + d_fgct) / dur : 0
            printf "GC:%.0fMB/s|%d|%.0fms|%d|%.0fms|%.2f%%",
                alloc_mb_per_s, d_ygc, d_ygct*1000.0, d_fgc, d_fgct*1000.0, gc_pct
        }' || true
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
LAST_GC_SUMMARY=""

# Convert wrk duration token (e.g. "10s", "30s", "1m") into seconds.
duration_to_seconds() {
    local d="$1"
    case "$d" in
        *m) echo $(( ${d%m} * 60 )) ;;
        *s) echo "${d%s}" ;;
        *)  echo "$d" ;;
    esac
}

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

    # GC capture: sample jstat just before the timed wrk run.
    GC_PRE=""
    GC_PID=""
    if [ "$GC_CAPTURE" = 1 ]; then
        GC_PID=$(find_server_pid)
        if [ -n "$GC_PID" ] && is_jvm_pid "$GC_PID"; then
            GC_PRE=$(jstat_sample "$GC_PID")
        fi
    fi

    # Benchmark
    RESULT=$(run_wrk "-t${WRK_THREADS}" "-c${WRK_CONNS}" "-d${WRK_DURATION}" --latency "${WRK_EXTRA_ARR[@]}" "${URL}" 2>&1)

    # GC capture: post-wrk sample + delta summary for this run.
    LAST_GC_SUMMARY=""
    if [ "$GC_CAPTURE" = 1 ] && [ -n "$GC_PRE" ] && [ -n "$GC_PID" ]; then
        GC_POST=$(jstat_sample "$GC_PID")
        if [ -n "$GC_POST" ]; then
            LAST_GC_SUMMARY=$(gc_summary "$GC_PRE" "$GC_POST" "$(duration_to_seconds "$WRK_DURATION")")
            # Persist raw jstat pre/post snapshots so a future analyst
            # can recompute metrics with a different formula. The
            # summary line's `alloc MB/s` is a proxy
            # (`YGC × Eden_end / duration`) and tied to G1's
            # end-of-run Eden capacity; the YGC / FGC / YGCT / FGCT
            # deltas are exact and recomputable from these snapshots.
            GC_RAW="${RESULTS_DIR}/${NAME//[^A-Za-z0-9_-]/_}-gc-${GC_TIMESTAMP}-run${run}.txt"
            {
                echo "# bench-remote.sh GC capture"
                echo "# name=$NAME engine_pid=$GC_PID port=$PORT"
                echo "# duration_seconds=$(duration_to_seconds "$WRK_DURATION") run=$run"
                echo "# summary=$LAST_GC_SUMMARY"
                echo "## pre"
                printf '%s\n' "$GC_PRE"
                echo "## post"
                printf '%s\n' "$GC_POST"
            } >"$GC_RAW"
        fi
    fi

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

# GC counters are from the last run only — multi-run medians of the GC
# counters are not computed (sample size too small to be statistically
# meaningful, and the throughput median already smooths run-to-run noise).
GC_SUFFIX=""
if [ -n "$LAST_GC_SUMMARY" ]; then
    GC_SUFFIX="|$LAST_GC_SUMMARY"
fi

if [ "$RUNS" -gt 1 ]; then
    MEDIAN_RPS=$(median "${ALL_RPS[@]}")
    echo "$NAME|$MEDIAN_RPS|$BEST_P50|$BEST_P99|[${ALL_RPS[*]}]${GC_SUFFIX}"
else
    echo "$NAME|${ALL_RPS[0]}|$BEST_P50|$BEST_P99${GC_SUFFIX}"
fi
