#!/usr/bin/env bash
# Benchmark all servers: cross-language reference + Kotlin/Native + JVM
#
# Usage: ./benchmark/bench-all.sh [profile]
#   profile: default (default), tuned, keel-equiv-0.1
#
# Environment variables:
#   BENCH_ENDPOINT       Endpoint to benchmark (default: /hello)
#   BENCH_RUNS           Number of runs per engine; median is reported (default: 1)
#   BENCH_SHUFFLE        Randomize engine order (default: false, set "true" to enable)
#   BENCH_COOLDOWN       Seconds to wait between engines for OS resource recovery (default: 2)
#   BENCH_WRK_THREADS    wrk threads (default: 4)
#   BENCH_WRK_CONNS      wrk connections (default: 100)
#   BENCH_WRK_DURATION   wrk duration (default: 10s)
#   BENCH_PORT           Starting port (default: 18090)
#   BENCH_HOST_LABEL     Hostname label for results directory
#   BENCH_SCHEME         http or https (default: http)
#   BENCH_TLS            TLS backend for --tls flag (e.g., jsse, openssl, awslc)
#
# Servers are run sequentially, never in parallel.
# Each server is started, warmed up, benchmarked, then killed before the next.

set -uo pipefail
cd "$(dirname "$0")/.."

PROFILE="${1:-default}"
PORT=${BENCH_PORT:-18090}
WRK_THREADS=${BENCH_WRK_THREADS:-4}
WRK_CONNS=${BENCH_WRK_CONNS:-100}
WRK_DURATION=${BENCH_WRK_DURATION:-10s}
ENDPOINT="${BENCH_ENDPOINT:-/hello}"
WARMUP_DURATION=3s
READY_TIMEOUT=30
RUNS=${BENCH_RUNS:-1}
SHUFFLE=${BENCH_SHUFFLE:-false}
COOLDOWN=${BENCH_COOLDOWN:-2}
SCHEME=${BENCH_SCHEME:-http}
TLS_BACKEND="${BENCH_TLS:-}"
RESULTS_BASE="benchmark/results"
HOST_LABEL="${BENCH_HOST_LABEL:-$(hostname -s)}"
RESULTS_DIR="${RESULTS_BASE}/${HOST_LABEL}"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)

mkdir -p "$RESULTS_DIR"

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

# --- Extract Req/sec from wrk output ---

extract_rps() {
    echo "$1" | grep "Requests/sec" | awk '{print $2}'
}

# --- Compute median over numeric entries only (see bench-one.sh) ---
median() {
    echo "$@" | tr ' ' '\n' \
        | awk '/^[0-9]+(\.[0-9]+)?$/' \
        | sort -n \
        | awk '{a[NR]=$1} END {
            if (NR==0) { print "FAILED"; exit }
            if (NR%2==1) print a[(NR+1)/2]
            else printf "%.2f\n", (a[NR/2]+a[NR/2+1])/2
        }'
}

# --- Per-cell diagnostic log dir (see bench-one.sh) ---
BENCH_LOG_DIR="${BENCH_LOG_DIR:-/tmp/keel-bench-diag}"
mkdir -p "$BENCH_LOG_DIR" 2>/dev/null || true

# --- Benchmark runner ---

run_bench() {
    local name="$1"
    shift
    local cmd=("$@")
    local all_rps=()
    local all_status=()
    local best_result=""
    local best_rps=0

    # Use a dedicated port for this engine, incremented once per engine (not per run).
    local engine_port="$PORT"
    local log_file="$BENCH_LOG_DIR/${name}-$(date +%Y%m%d-%H%M%S).log"
    log_msg() { printf '[%s] %s\n' "$(date +%T)" "$*" >> "$log_file"; }
    log_msg "=== run_bench start name=$name scheme=$SCHEME endpoint=$ENDPOINT port=$engine_port runs=$RUNS ==="
    log_msg "argv: ${cmd[*]}"

    for run in $(seq 1 "$RUNS"); do
        log_msg "--- run $run/$RUNS ---"
        # Defensive cleanup: kill any process still holding the port from a
        # previous run or a partially-terminated engine. On macOS, server-side
        # sockets can linger if SO_REUSEADDR is not set, blocking a new bind.
        kill_port "$engine_port"
        wait_port_free "$engine_port"

        # Start server
        if command -v setsid >/dev/null 2>&1; then
            setsid "${cmd[@]}" >/dev/null 2>&1 &
        else
            "${cmd[@]}" >/dev/null 2>&1 &
        fi
        local pid=$!
        log_msg "server pid=$pid"

        # Wait for server to be ready. Validate HTTP status, not just
        # TCP connect — see bench-one.sh for the rationale.
        # Track per-iteration curl exit codes so a `FAILED` cell can be
        # attributed (exit 7 = TCP refused, exit 28 = server hung —
        # K55-class fingerprint, exit 0 + non-2xx/3xx = warmup error).
        local ready=false
        local -A curl_exit_counts=()
        local last_curl_exit=0
        local last_status=000
        local iter status curl_exit
        for iter in $(seq 1 "$READY_TIMEOUT"); do
            status=$(curl -sk --max-time 2 -o /dev/null -w '%{http_code}' \
                "${SCHEME}://127.0.0.1:${engine_port}${ENDPOINT}" 2>/dev/null)
            curl_exit=$?
            last_curl_exit=$curl_exit
            last_status=$status
            curl_exit_counts[$curl_exit]=$(( ${curl_exit_counts[$curl_exit]:-0} + 1 ))
            case "$status" in
                2??|3??) ready=true; break ;;
            esac
            sleep 0.3
        done
        log_msg "READY phase: $iter iters, curl_exit_dist=$(declare -p curl_exit_counts | sed 's/^[^=]*=//')"

        if [ "$ready" = false ]; then
            local best_exit=0 best_count=-1 e c
            for e in "${!curl_exit_counts[@]}"; do
                c=${curl_exit_counts[$e]}
                if [ "$c" -gt "$best_count" ] || { [ "$c" -eq "$best_count" ] && [ "$e" != 0 ]; }; then
                    best_exit=$e
                    best_count=$c
                fi
            done
            local status_token="READY_TIMEOUT_${best_exit}"
            log_msg "READY FAILED attribution=$status_token (count=$best_count/$iter)"
            all_rps+=("$status_token")
            all_status+=("$status_token")
            kill_port "$engine_port"
            kill "$pid" 2>/dev/null || true
            wait "$pid" 2>/dev/null || true
            wait_port_free "$engine_port"
            if [ "$run" -lt "$RUNS" ]; then
                sleep "$COOLDOWN"
            fi
            continue
        fi
        log_msg "READY ok after $iter iters (last_status=$last_status)"

        # Warmup
        wrk -t2 -c10 -d"${WARMUP_DURATION}" "${SCHEME}://127.0.0.1:${engine_port}${ENDPOINT}" >/dev/null 2>&1

        # Benchmark
        local result
        result=$(wrk -t"${WRK_THREADS}" -c"${WRK_CONNS}" -d"${WRK_DURATION}" --latency "${SCHEME}://127.0.0.1:${engine_port}${ENDPOINT}" 2>&1)

        local rps
        rps=$(extract_rps "$result")

        # Crash vs incomplete-output: kill -0 before kill_server.
        local server_alive=true
        if ! kill -0 "$pid" 2>/dev/null; then
            server_alive=false
        fi

        if [ -z "$rps" ]; then
            local status_token
            if [ "$server_alive" = false ]; then
                status_token="CRASH"
                log_msg "RUN $run FAILED: server pid=$pid dead before kill (CRASH)"
            else
                status_token="WRK_INCOMPLETE"
                log_msg "RUN $run FAILED: wrk produced no Requests/sec line, server still alive"
            fi
            log_msg "--- wrk output begin ---"
            echo "$result" >> "$log_file"
            log_msg "--- wrk output end ---"
            all_rps+=("$status_token")
            all_status+=("$status_token")
        else
            log_msg "RUN $run OK rps=$rps (server_alive=$server_alive)"
            all_rps+=("$rps")
            all_status+=("OK")
            if awk "BEGIN {exit !($rps > $best_rps)}" 2>/dev/null; then
                best_rps="$rps"
                best_result="$result"
            fi
        fi

        # Stop server
        kill_port "$engine_port"
        kill "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
        wait_port_free "$engine_port"

        # Cooldown between runs of same engine
        if [ "$run" -lt "$RUNS" ]; then
            sleep "$COOLDOWN"
        fi
    done

    # Move to next port for next engine
    PORT=$((engine_port + 1))

    # Save raw wrk output (best run)
    local safe_name endpoint_name result_file
    safe_name=$(echo "$name" | tr ':/' '-')
    endpoint_name=$(echo "$ENDPOINT" | tr '/' '-' | sed 's/^-//')
    result_file="${RESULTS_DIR}/${safe_name}-${endpoint_name}-${WRK_THREADS}t${WRK_CONNS}c-${TIMESTAMP}.txt"
    echo "$best_result" > "$result_file"

    local ok_count=0 s
    for s in "${all_status[@]}"; do
        [ "$s" = "OK" ] && ok_count=$((ok_count + 1))
    done

    local cell_status
    if [ "$ok_count" -eq "$RUNS" ]; then
        cell_status="OK"
    elif [ "$ok_count" -gt 0 ]; then
        cell_status="PARTIAL(${ok_count}/${RUNS})"
    else
        local first="${all_status[0]}" all_same=true
        for s in "${all_status[@]}"; do
            [ "$s" != "$first" ] && { all_same=false; break; }
        done
        if [ "$all_same" = true ]; then
            cell_status="$first"
        else
            cell_status="MIXED_FAILED"
        fi
    fi
    log_msg "=== run_bench end status=$cell_status ok=$ok_count/$RUNS ==="
    log_msg "all_status=(${all_status[*]})"

    # Compute median rps
    local median_rps
    if [ "$ok_count" -gt 0 ]; then
        median_rps=$(median "${all_rps[@]}")
    else
        median_rps="FAILED"
    fi

    local lat50 lat99 errors
    # Anchor on `^   50%   ` shape; otherwise wrk's `Req/Sec ... 50.99%`
    # +/- Stdev band can be misread as a percentile.
    lat50=$(echo "$best_result" | awk '/^[[:space:]]+50%[[:space:]]/ {print $2; exit}')
    lat99=$(echo "$best_result" | awk '/^[[:space:]]+99%[[:space:]]/ {print $2; exit}')
    errors=$(echo "$best_result" | grep "Socket errors" | head -1)

    if [ "$RUNS" -gt 1 ]; then
        printf "  %-24s %12s req/s  p50=%-10s p99=%-10s [%s] (%d runs) [%s]" "$name" "$median_rps" "${lat50:--}" "${lat99:--}" "${all_rps[*]}" "$RUNS" "$cell_status"
    else
        printf "  %-24s %12s req/s  p50=%-10s p99=%-10s [%s]" "$name" "$median_rps" "${lat50:--}" "${lat99:--}" "$cell_status"
    fi
    if [ -n "$errors" ]; then
        echo "  $errors"
    else
        echo ""
    fi

    # Cooldown between engines
    sleep "$COOLDOWN"
}

# --- Run optional binary (skip if not found) ---

run_if_exists() {
    local name="$1" binary="$2"
    shift 2
    if [ -f "$binary" ]; then
        run_bench "$name" "$binary" "$@"
    fi
}

# --- Build engine list ---

build_engine_list() {
    local engines=()

    PROFILE_ARGS=""
    if [ "$PROFILE" != "default" ]; then
        PROFILE_ARGS="--profile=${PROFILE}"
    fi

    # Cross-language reference servers
    for pair in \
        "rust-bench:benchmark/rust-bench/target/release/rust-bench" \
        "go-bench:benchmark/go-bench/go-bench" \
        "swift-bench:benchmark/swift-bench/.build/release/swift-bench" \
        "zig-bench:benchmark/zig-bench/zig-out/bin/zig-bench"; do
        local ename="${pair%%:*}"
        local ebin="${pair#*:}"
        if [ -f "$ebin" ]; then
            engines+=("native-bin:${ename}:${ebin}")
        fi
    done

    # Kotlin/Native servers
    NATIVE_BIN=""
    if [ "$(uname)" = "Darwin" ]; then
        ARCH=$(uname -m)
        if [ "$ARCH" = "arm64" ]; then
            NATIVE_BIN="benchmark/build/bin/macosArm64/releaseExecutable/benchmark.kexe"
        else
            NATIVE_BIN="benchmark/build/bin/macosX64/releaseExecutable/benchmark.kexe"
        fi
        if [ -f "$NATIVE_BIN" ]; then
            for engine in ktor-keel-kqueue pipeline-http-kqueue server-http-kqueue ktor-cio-keel-kqueue ktor-keel-nwconnection ktor-cio-keel-nwconnection pipeline-http-nwconnection server-http-nwconnection ktor-cio; do
                engines+=("kn-engine:native:${engine}:${NATIVE_BIN}")
            done
        fi
    elif [ "$(uname)" = "Linux" ]; then
        NATIVE_BIN="benchmark/build/bin/linuxX64/releaseExecutable/benchmark.kexe"
        if [ -f "$NATIVE_BIN" ]; then
            for engine in ktor-keel-epoll pipeline-http-epoll server-http-epoll ktor-cio-keel-epoll ktor-keel-io-uring pipeline-http-io-uring server-http-io-uring ktor-cio-keel-io-uring raw-io-uring ktor-cio; do
                engines+=("kn-engine:native:${engine}:${NATIVE_BIN}")
            done
        fi
    fi

    # JVM servers
    JVM_CP_FILE="benchmark/build/benchmark-classpath.txt"
    if [ -f "$JVM_CP_FILE" ]; then
        for engine in ktor-keel-nio pipeline-http-nio server-http-nio ktor-cio-keel-nio ktor-keel-netty ktor-cio-keel-netty pipeline-http-netty server-http-netty ktor-cio ktor-netty netty-raw spring vertx; do
            engines+=("jvm-engine:jvm:${engine}")
        done
    fi

    # JS (Node.js) server
    JS_BIN="benchmark/build/compileSync/js/main/productionExecutable/kotlin/keel-benchmark.js"
    if [ -f "$JS_BIN" ]; then
        engines+=("js-engine:js:pipeline-http-nodejs:${JS_BIN}")
    fi

    # Shuffle if requested
    if [ "$SHUFFLE" = "true" ]; then
        local shuffled
        shuffled=$(printf '%s\n' "${engines[@]}" | sort -R)
        engines=()
        while IFS= read -r line; do
            engines+=("$line")
        done <<< "$shuffled"
    fi

    printf '%s\n' "${engines[@]}"
}

# --- Main ---

echo "=== Benchmark: ${SCHEME}${ENDPOINT} (${WRK_THREADS}t/${WRK_CONNS}c/${WRK_DURATION}) profile=${PROFILE} runs=${RUNS} shuffle=${SHUFFLE} cooldown=${COOLDOWN}s ==="
echo ""
printf "  %-24s %12s        %-10s  %-10s\n" "Server" "Req/sec" "p50" "p99"
printf "  %-24s %12s        %-10s  %-10s\n" "------------------------" "------------" "----------" "----------"

JVM_CP_FILE="benchmark/build/benchmark-classpath.txt"
JVM_CP=""
if [ -f "$JVM_CP_FILE" ]; then
    JVM_CP=$(cat "$JVM_CP_FILE")
fi

PROFILE_ARGS=""
if [ "$PROFILE" != "default" ]; then
    PROFILE_ARGS="--profile=${PROFILE}"
fi

TLS_ARGS=""
if [ -n "$TLS_BACKEND" ]; then
    TLS_ARGS="--tls=${TLS_BACKEND}"
fi

while IFS= read -r entry; do
    type="${entry%%:*}"
    rest="${entry#*:}"
    case "$type" in
        native-bin)
            ename="${rest%%:*}"
            ebin="${rest#*:}"
            run_bench "$ename" "$ebin" --port="${PORT}" ${PROFILE_ARGS} ${TLS_ARGS}
            ;;
        kn-engine)
            # format: native:<engine>:<binary>
            display="${rest%%:*}"  # "native"
            rest2="${rest#*:}"
            engine="${rest2%%:*}"
            binary="${rest2#*:}"
            run_bench "${display}:${engine}" "$binary" --engine="${engine}" --port="${PORT}" ${PROFILE_ARGS} ${TLS_ARGS}
            ;;
        jvm-engine)
            # format: jvm:<engine>
            display="${rest%%:*}"
            engine="${rest#*:}"
            if [ -n "$JVM_CP" ]; then
                run_bench "${display}:${engine}" java -cp "$JVM_CP" io.github.fukusaka.keel.benchmark.JvmMainKt --engine="${engine}" --port="${PORT}" ${PROFILE_ARGS} ${TLS_ARGS}
            fi
            ;;
        js-engine)
            # format: js:<engine>:<binary>
            display="${rest%%:*}"
            rest2="${rest#*:}"
            engine="${rest2%%:*}"
            binary="${rest2#*:}"
            run_bench "${display}:${engine}" node "$binary" --engine="${engine}" --port="${PORT}" ${PROFILE_ARGS} ${TLS_ARGS}
            ;;
    esac
done < <(build_engine_list)

echo ""
echo "=== Done ==="
