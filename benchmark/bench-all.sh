#!/usr/bin/env bash
# Benchmark all servers: Phase 2 native + Kotlin/Native + JVM
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

# --- Extract Req/sec from wrk output ---

extract_rps() {
    echo "$1" | grep "Requests/sec" | awk '{print $2}'
}

# --- Compute median of space-separated numbers ---

median() {
    echo "$@" | tr ' ' '\n' | sort -n | awk '{a[NR]=$1} END {
        if (NR%2==1) print a[(NR+1)/2]
        else printf "%.2f\n", (a[NR/2]+a[NR/2+1])/2
    }'
}

# --- Benchmark runner ---

run_bench() {
    local name="$1"
    shift
    local cmd=("$@")
    local all_rps=()
    local best_result=""
    local best_rps=0

    # Use a dedicated port for this engine, incremented once per engine (not per run).
    local engine_port="$PORT"

    for run in $(seq 1 "$RUNS"); do
        # Start server
        if command -v setsid >/dev/null 2>&1; then
            setsid "${cmd[@]}" >/dev/null 2>&1 &
        else
            "${cmd[@]}" >/dev/null 2>&1 &
        fi
        local pid=$!

        # Wait for server to be ready
        local ready=false
        for _ in $(seq 1 "$READY_TIMEOUT"); do
            if curl -sk -o /dev/null "${SCHEME}://127.0.0.1:${engine_port}${ENDPOINT}" 2>/dev/null; then
                ready=true
                break
            fi
            sleep 0.3
        done

        if [ "$ready" = false ]; then
            printf "  %-24s %s\n" "$name" "FAILED TO START"
            kill_port "$engine_port"
            kill "$pid" 2>/dev/null || true
            wait "$pid" 2>/dev/null || true
            PORT=$((engine_port + 1))
            return
        fi

        # Warmup
        wrk -t2 -c10 -d"${WARMUP_DURATION}" "${SCHEME}://127.0.0.1:${engine_port}${ENDPOINT}" >/dev/null 2>&1

        # Benchmark
        local result
        result=$(wrk -t"${WRK_THREADS}" -c"${WRK_CONNS}" -d"${WRK_DURATION}" --latency "${SCHEME}://127.0.0.1:${engine_port}${ENDPOINT}" 2>&1)

        local rps
        rps=$(extract_rps "$result")
        all_rps+=("$rps")

        # Keep result with highest rps for raw output file and display
        if [ -n "$rps" ] && awk "BEGIN {exit !($rps > $best_rps)}" 2>/dev/null; then
            best_rps="$rps"
            best_result="$result"
        fi

        # Stop server
        kill_port "$engine_port"
        kill "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true

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

    # Compute median rps
    local median_rps
    if [ "$RUNS" -gt 1 ]; then
        median_rps=$(median "${all_rps[@]}")
    else
        median_rps="${all_rps[0]}"
    fi

    local lat50 lat99 errors
    lat50=$(echo "$best_result" | grep "50%" | awk '{print $2}')
    lat99=$(echo "$best_result" | grep "99%" | awk '{print $2}')
    errors=$(echo "$best_result" | grep "Socket errors" | head -1)

    if [ "$RUNS" -gt 1 ]; then
        printf "  %-24s %12s req/s  p50=%-10s p99=%-10s [%s] (%d runs)" "$name" "$median_rps" "$lat50" "$lat99" "${all_rps[*]}" "$RUNS"
    else
        printf "  %-24s %12s req/s  p50=%-10s p99=%-10s" "$name" "$median_rps" "$lat50" "$lat99"
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

    # Phase 2: Native servers
    for pair in \
        "rust-hello:benchmark/rust-hello/target/release/rust-hello" \
        "go-hello:benchmark/go-hello/go-hello" \
        "swift-hello:benchmark/swift-hello/.build/release/swift-hello" \
        "zig-hello:benchmark/zig-hello/zig-out/bin/zig-hello"; do
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
            for engine in ktor-keel-kqueue pipeline-http-kqueue ktor-keel-nwconnection pipeline-http-nwconnection ktor-cio; do
                engines+=("kn-engine:native:${engine}:${NATIVE_BIN}")
            done
        fi
    elif [ "$(uname)" = "Linux" ]; then
        NATIVE_BIN="benchmark/build/bin/linuxX64/releaseExecutable/benchmark.kexe"
        if [ -f "$NATIVE_BIN" ]; then
            for engine in ktor-keel-epoll pipeline-http-epoll ktor-keel-io-uring pipeline-http-io-uring raw-io-uring ktor-cio; do
                engines+=("kn-engine:native:${engine}:${NATIVE_BIN}")
            done
        fi
    fi

    # JVM servers
    JVM_CP_FILE="benchmark/build/benchmark-classpath.txt"
    if [ -f "$JVM_CP_FILE" ]; then
        for engine in ktor-keel-nio pipeline-http-nio ktor-keel-netty ktor-cio ktor-netty netty-raw spring vertx; do
            engines+=("jvm-engine:jvm:${engine}")
        done
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
    esac
done < <(build_engine_list)

echo ""
echo "=== Done ==="
