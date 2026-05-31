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
#   BENCH_SCHEME         http or https (default: http)
#
# Example:
#   ./benchmark/bench-one.sh rust-bench benchmark/rust-bench/target/release/rust-bench --port=18090
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
WARMUP_DURATION=${BENCH_WARMUP:-3s}
SCHEME=${BENCH_SCHEME:-http}
READY_TIMEOUT=${BENCH_READY_TIMEOUT:-60}

# Extract --port=N from args if present
for arg in "$@"; do
    case "$arg" in
        --port=*) PORT="${arg#--port=}" ;;
    esac
done

# --- Port management (cross-platform) ---

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
    kill $pids 2>/dev/null || return 0       # SIGTERM first
    for _ in $(seq 1 20); do                 # wait up to 2s
        kill -0 $pids 2>/dev/null || return 0
        sleep 0.1
    done
    kill -9 $pids 2>/dev/null || true        # SIGKILL fallback
}

# wait_port_free: poll until no process holds the port, or timeout.
# On macOS, server-side TIME_WAIT sockets can block a new bind() for up to
# 60 s when SO_REUSEADDR is not set. After kill_port+wait, check with lsof
# (which shows TIME_WAIT sockets on macOS via open files) before declaring
# the port free. Linux fuser is used on Linux for the same check.
wait_port_free() {
    local port="$1"
    local max_wait="${2:-15}"
    local elapsed=0
    while [ "$elapsed" -lt "$max_wait" ]; do
        local busy=false
        if [ "$(uname)" = "Linux" ] && command -v fuser >/dev/null 2>&1; then
            fuser "$port"/tcp >/dev/null 2>&1 && busy=true
        elif command -v lsof >/dev/null 2>&1; then
            lsof -ti :"$port" >/dev/null 2>&1 && busy=true
        else
            return 0  # can't check — assume free
        fi
        [ "$busy" = false ] && return 0
        sleep 1
        elapsed=$((elapsed + 1))
    done
    printf "  [warn] port %d still busy after %ds — proceeding anyway\n" "$port" "$max_wait" >&2
}

# --- Compute median over numeric entries only ---
#
# Non-numeric tokens (e.g. "READY_TIMEOUT_28", "CRASH", "WRK_INCOMPLETE")
# represent failed runs and are skipped. Empty entries cannot occur because
# the per-run logic always appends a status token instead of "".
median() {
    echo "$@" | tr ' ' '\n' \
        | awk '/^[0-9]+(\.[0-9]+)?$/' \
        | sort -n \
        | awk '{a[NR]=$1} END {
            if (NR==0) { print "NaN"; exit }
            if (NR%2==1) print a[(NR+1)/2]
            else printf "%.2f\n", (a[NR/2]+a[NR/2+1])/2
        }'
}

# --- Per-cell diagnostic log ---
#
# Failure attribution lives here: each READY-check curl exit code, each wrk
# run's incompleteness (RPS-extraction failure), server PID liveness at kill
# time, and the cumulative per-run status. The result line that goes to the
# sweep script remains a one-liner; the log lets a human reconstruct WHY a
# cell ended up FAILED / PARTIAL / OK without re-running.
LOG_DIR="${BENCH_LOG_DIR:-/tmp/keel-bench-diag}"
mkdir -p "$LOG_DIR" 2>/dev/null || true
LOG_FILE="$LOG_DIR/${NAME}-$(date +%Y%m%d-%H%M%S).log"

log() { printf '[%s] %s\n' "$(date +%T)" "$*" >> "$LOG_FILE"; }
log "=== bench-one.sh start name=$NAME scheme=$SCHEME endpoint=$ENDPOINT port=$PORT runs=$RUNS ==="
log "argv: $*"

# --- Run benchmark ---

ALL_RPS=()       # Numeric on success, or status token ("READY_TIMEOUT_NN" / "CRASH" / "WRK_INCOMPLETE") on failure
ALL_STATUS=()    # Mirror of ALL_RPS but always a status token ("OK" / "READY_TIMEOUT_NN" / "CRASH" / "WRK_INCOMPLETE")
BEST_RPS=0
BEST_P50=""
BEST_P99=""

for run in $(seq 1 "$RUNS"); do
    log "--- run $run/$RUNS ---"
    # Kill any process holding the port and wait until it is confirmed free.
    # Replaces the former unconditional sleep 1: wait_port_free polls lsof/fuser
    # instead of guessing a safe delay, which avoids READY timeout on macOS
    # when a previous engine's server-side socket lingers in TIME_WAIT.
    kill_port "$PORT"
    wait_port_free "$PORT"

    # Start server. setsid makes the child a session leader so its PID
    # is also the PGID — letting `kill_server` send signals to the
    # whole process group so grandchildren (helper threads, JVM forks)
    # don't leak and hold the bench port across runs.
    USED_SETSID=false
    if command -v setsid >/dev/null 2>&1; then
        setsid "$@" >/dev/null 2>&1 &
        USED_SETSID=true
    else
        "$@" >/dev/null 2>&1 &
    fi
    PID=$!
    log "server pid=$PID setsid=$USED_SETSID"

    kill_server() {
        if [ "$USED_SETSID" = true ]; then
            kill -TERM -- "-$PID" 2>/dev/null || true
        else
            kill "$PID" 2>/dev/null || true
        fi
    }

    # Wait for server to be ready. Validate HTTP status, not just TCP
    # connect — without `-w '%{http_code}'` curl returns 0 even on 5xx
    # responses, so a half-broken engine would still be marked ready.
    #
    # Track per-iteration curl exit codes so a `FAILED` cell can later be
    # attributed to "TCP refused throughout" (exit 7), "server accepted TCP
    # but never responded" (exit 28 = CURLE_OPERATION_TIMEDOUT, the server-hang
    # signal), or "server returned a 4xx/5xx during warmup" (status code).
    READY=false
    declare -A CURL_EXIT_COUNTS=()
    declare -A STATUS_COUNTS=()
    LAST_CURL_EXIT=0
    LAST_STATUS=000
    for iter in $(seq 1 "$READY_TIMEOUT"); do
        STATUS=$(curl -sk --max-time 2 -o /dev/null -w '%{http_code}' \
            "${SCHEME}://127.0.0.1:${PORT}${ENDPOINT}" 2>/dev/null)
        CURL_EXIT=$?
        LAST_CURL_EXIT=$CURL_EXIT
        LAST_STATUS=$STATUS
        CURL_EXIT_COUNTS[$CURL_EXIT]=$(( ${CURL_EXIT_COUNTS[$CURL_EXIT]:-0} + 1 ))
        STATUS_COUNTS[$STATUS]=$(( ${STATUS_COUNTS[$STATUS]:-0} + 1 ))
        case "$STATUS" in
            2??|3??) READY=true; break ;;
        esac
        sleep 0.5
    done

    # Dump distribution
    log "READY phase: $iter iters, curl_exit_dist=$(declare -p CURL_EXIT_COUNTS 2>/dev/null | sed 's/^[^=]*=//') status_dist=$(declare -p STATUS_COUNTS 2>/dev/null | sed 's/^[^=]*=//')"

    if [ "$READY" = false ]; then
        log "READY FAILED last_curl_exit=$LAST_CURL_EXIT last_status=$LAST_STATUS"
        # Pick the dominant curl exit as the failure attribution.
        # Tie-break: prefer non-zero exits (informative) over 0 (which only
        # appears when HTTP status was 4xx/5xx).
        BEST_EXIT=0
        BEST_COUNT=-1
        for e in "${!CURL_EXIT_COUNTS[@]}"; do
            c=${CURL_EXIT_COUNTS[$e]}
            if [ "$c" -gt "$BEST_COUNT" ] || { [ "$c" -eq "$BEST_COUNT" ] && [ "$e" != 0 ]; }; then
                BEST_EXIT=$e
                BEST_COUNT=$c
            fi
        done
        STATUS_TOKEN="READY_TIMEOUT_${BEST_EXIT}"
        log "READY attribution: $STATUS_TOKEN (count=$BEST_COUNT/${READY_TIMEOUT})"
        ALL_RPS+=("$STATUS_TOKEN")
        ALL_STATUS+=("$STATUS_TOKEN")
        kill_port "$PORT"
        kill_server
        wait "$PID" 2>/dev/null || true
        wait_port_free "$PORT"
        if [ "$run" -lt "$RUNS" ]; then
            sleep "$COOLDOWN"
        fi
        continue
    fi
    log "READY ok after $iter iters (last_status=$LAST_STATUS)"

    # Warmup
    wrk -t2 -c10 -d"${WARMUP_DURATION}" "${SCHEME}://127.0.0.1:${PORT}${ENDPOINT}" >/dev/null 2>&1

    # Benchmark
    RESULT=$(wrk -t"${WRK_THREADS}" -c"${WRK_CONNS}" -d"${WRK_DURATION}" --latency "${SCHEME}://127.0.0.1:${PORT}${ENDPOINT}" 2>&1)

    RPS=$(echo "$RESULT" | grep "Requests/sec" | awk '{print $2}')
    # Latency Distribution rows look like `   50%    1.20ms`. Anchor on the
    # full shape (whitespace + `50%` + whitespace) so we don't misread the
    # Thread Stats `Req/Sec ... 50.99%` (column-4 +/- Stdev band) as a
    # percentile and put `14.11k` into P50/P99.
    P50=$(echo "$RESULT" | awk '/^[[:space:]]+50%[[:space:]]/ {print $2; exit}')
    P99=$(echo "$RESULT" | awk '/^[[:space:]]+99%[[:space:]]/ {print $2; exit}')

    # Stop the server and capture its exit status. `wait` returns the
    # child's real exit code, encoding fatal signals as `128 + signum`.
    # We need to detect the signal that actually killed the server, not
    # whatever we sent — `kill_server`'s SIGTERM and `kill_port`'s SIGKILL
    # are ours, but SIGSEGV / SIGABRT / SIGBUS can only come from the
    # server itself (we never send those). So an exit of 139 / 134 / 138
    # is definitive evidence of a server crash, even when our SIGTERM
    # raced with it.
    #
    # Exit-status decode: the pre-fix logic only did `kill -0 $PID` *before* kill_server,
    # missing the race where the server SEGV'd between that check and our
    # SIGTERM arriving. Worse, wrk could already have a numeric Requests/sec
    # line from the partial run before the crash, so the cell silently
    # logged as OK with a misleading low rps (one run on
    # `pipeline-http × nwconn × mbedtls` dropped to 4 K rps vs 47 K sibling).
    kill_port "$PORT"
    kill_server
    wait "$PID" 2>/dev/null
    SERVER_EXIT_STATUS=$?

    SERVER_DIED_BY=""
    if [ "$SERVER_EXIT_STATUS" -gt 128 ]; then
        case $((SERVER_EXIT_STATUS - 128)) in
            15) SERVER_DIED_BY="SIGTERM" ;;     # our kill_server
            9)  SERVER_DIED_BY="SIGKILL" ;;     # our kill_port fallback (or OOM)
            11) SERVER_DIED_BY="SIGSEGV" ;;     # crash
            6)  SERVER_DIED_BY="SIGABRT" ;;     # crash
            10) SERVER_DIED_BY="SIGBUS" ;;      # crash
            7)  SERVER_DIED_BY="SIGBUS" ;;      # crash (Linux numbering)
            8)  SERVER_DIED_BY="SIGFPE" ;;      # crash
            4)  SERVER_DIED_BY="SIGILL" ;;      # crash
            *)  SERVER_DIED_BY="SIG$((SERVER_EXIT_STATUS - 128))" ;;
        esac
    fi

    # Was it a fatal signal originated by the server itself (i.e. not by us)?
    SERVER_CRASHED=false
    case "$SERVER_DIED_BY" in
        SIGSEGV|SIGABRT|SIGBUS|SIGFPE|SIGILL) SERVER_CRASHED=true ;;
    esac

    if [ -z "$RPS" ]; then
        if [ "$SERVER_CRASHED" = true ]; then
            STATUS_TOKEN="CRASH"
            log "RUN $run FAILED: server died by $SERVER_DIED_BY (exit $SERVER_EXIT_STATUS) — wrk output saved below"
        else
            STATUS_TOKEN="WRK_INCOMPLETE"
            log "RUN $run FAILED: wrk produced no Requests/sec line, server died_by=$SERVER_DIED_BY (exit $SERVER_EXIT_STATUS) — wrk output saved below"
        fi
        log "--- wrk output begin ---"
        echo "$RESULT" >> "$LOG_FILE"
        log "--- wrk output end ---"
        ALL_RPS+=("$STATUS_TOKEN")
        ALL_STATUS+=("$STATUS_TOKEN")
    elif [ "$SERVER_CRASHED" = true ]; then
        # wrk emitted a Requests/sec line, but the server died by a fatal
        # signal before our shutdown reached it — the partial throughput
        # is misleading. Override OK to CRASH so the runs array doesn't
        # hide the failure inside an apparently-numeric value.
        STATUS_TOKEN="CRASH"
        log "RUN $run FAILED: wrk reported rps=$RPS but server died by $SERVER_DIED_BY (exit $SERVER_EXIT_STATUS) — partial throughput unreliable, overriding to CRASH; wrk output saved below"
        log "--- wrk output begin ---"
        echo "$RESULT" >> "$LOG_FILE"
        log "--- wrk output end ---"
        ALL_RPS+=("$STATUS_TOKEN")
        ALL_STATUS+=("$STATUS_TOKEN")
    else
        log "RUN $run OK rps=$RPS p50=$P50 p99=$P99 (server_died_by=$SERVER_DIED_BY exit=$SERVER_EXIT_STATUS)"
        ALL_RPS+=("$RPS")
        ALL_STATUS+=("OK")
        if awk "BEGIN {exit !($RPS > $BEST_RPS)}" 2>/dev/null; then
            BEST_RPS="$RPS"
            BEST_P50="$P50"
            BEST_P99="$P99"
        fi
    fi

    wait_port_free "$PORT"

    # Cooldown between runs
    if [ "$run" -lt "$RUNS" ]; then
        sleep "$COOLDOWN"
    fi
done

# --- Compute summary status ---
ok_count=0
for s in "${ALL_STATUS[@]}"; do
    [ "$s" = "OK" ] && ok_count=$((ok_count + 1))
done

if [ "$ok_count" -eq "$RUNS" ]; then
    CELL_STATUS="OK"
elif [ "$ok_count" -gt 0 ]; then
    CELL_STATUS="PARTIAL(${ok_count}/${RUNS})"
else
    # All runs failed with the same status token? Use it. Otherwise MIXED.
    first_status="${ALL_STATUS[0]}"
    all_same=true
    for s in "${ALL_STATUS[@]}"; do
        [ "$s" != "$first_status" ] && { all_same=false; break; }
    done
    if [ "$all_same" = true ]; then
        CELL_STATUS="$first_status"
    else
        CELL_STATUS="MIXED_FAILED"
    fi
fi
log "=== bench-one.sh end status=$CELL_STATUS ok=$ok_count/$RUNS ==="
log "ALL_STATUS=(${ALL_STATUS[*]})"

# --- Output ---
#
# Backward compat: existing sweep scripts parse fields 2..5 (rps, p50, p99,
# runs). The new field 6 (status) is additive — scripts that ignore it keep
# working; scripts that want to surface CRASH / READY_TIMEOUT_28 / PARTIAL
# can read it.
if [ "$RUNS" -gt 1 ]; then
    if [ "$ok_count" -gt 0 ]; then
        MEDIAN_RPS=$(median "${ALL_RPS[@]}")
    else
        MEDIAN_RPS="FAILED"
    fi
    echo "$NAME|$MEDIAN_RPS|$BEST_P50|$BEST_P99|[${ALL_RPS[*]}]|$CELL_STATUS"
else
    SINGLE_VAL="${ALL_RPS[0]:-FAILED}"
    # If the single run failed, normalise to FAILED for the rps field.
    case "$SINGLE_VAL" in
        READY_TIMEOUT_*|CRASH|WRK_INCOMPLETE) SINGLE_VAL="FAILED" ;;
    esac
    echo "$NAME|$SINGLE_VAL|${BEST_P50:--}|${BEST_P99:--}|[${ALL_RPS[*]}]|$CELL_STATUS"
fi

# Exit non-zero if no successful runs, matching the old contract that a
# READY-failed cell exits 1 so the sweep script's `$?` check (where any)
# stays meaningful.
[ "$ok_count" -gt 0 ] || exit 1
