#!/usr/bin/env bash
# Benchmark all servers across all streaming scenarios (upload, sse, ws-echo, etc.)
# using bench-stream-one.sh as the per-engine primitive.
#
# Usage: ./benchmark/bench-stream-all.sh [scenarios...]
#   scenarios: space-separated list of scenarios to run (default: all non-compression)
#              e.g. "upload sse ws-echo"
#
# All BENCH_* env vars from bench-stream-one.sh are forwarded verbatim.
#
# Environment variables:
#   BENCH_RUNS           Number of runs per engine per scenario; median reported (default: 1)
#   BENCH_SHUFFLE        Randomize engine order within each scenario (default: false)
#   BENCH_COOLDOWN       Seconds between engines (default: 2)
#   BENCH_PORT           Starting port (default: 18090)
#   BENCH_HOST_LABEL     Hostname label for results directory
#
# Example:
#   BENCH_RUNS=3 BENCH_SHUFFLE=true ./benchmark/bench-stream-all.sh
#   BENCH_RUNS=2 ./benchmark/bench-stream-all.sh upload sse

set -uo pipefail
cd "$(dirname "$0")/.."

SHUFFLE=${BENCH_SHUFFLE:-false}
PORT=${BENCH_PORT:-18090}
COOLDOWN=${BENCH_COOLDOWN:-2}
RESULTS_BASE="benchmark/results"
HOST_LABEL="${BENCH_HOST_LABEL:-$(hostname -s)}"
RESULTS_DIR="${RESULTS_BASE}/${HOST_LABEL}"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
mkdir -p "$RESULTS_DIR"

# Default scenario list (all non-compression scenarios)
DEFAULT_SCENARIOS="upload sse ws-echo ws-large ws-fragment ws-slow-consumer multipart method-mix path-param slow-upload"

if [ $# -gt 0 ]; then
    SCENARIOS="$*"
else
    SCENARIOS="$DEFAULT_SCENARIOS"
fi

# --- Build engine list (format: "<display>|<command...>") ---
# Mirrors the engine detection logic in bench-all.sh.

build_engine_list() {
    local engines=()

    # Cross-language reference servers
    for pair in \
        "rust-bench|benchmark/rust-bench/target/release/rust-bench --port=${PORT}" \
        "go-bench|benchmark/go-bench/go-bench --port=${PORT}" \
        "swift-bench|benchmark/swift-bench/.build/release/swift-bench --port=${PORT}" \
        "zig-bench|benchmark/zig-bench/zig-out/bin/zig-bench --port=${PORT}"; do
        local display="${pair%%|*}"
        local cmd="${pair#*|}"
        local binary="${cmd%% *}"
        if [ -f "$binary" ]; then
            engines+=("${display}|${cmd}")
        fi
    done

    # Kotlin/Native servers
    if [ "$(uname)" = "Darwin" ]; then
        ARCH=$(uname -m)
        if [ "$ARCH" = "arm64" ]; then
            NATIVE_BIN="benchmark/build/bin/macosArm64/releaseExecutable/benchmark.kexe"
        else
            NATIVE_BIN="benchmark/build/bin/macosX64/releaseExecutable/benchmark.kexe"
        fi
        if [ -f "$NATIVE_BIN" ]; then
            for engine in ktor-cio-keel-kqueue ktor-keel-kqueue pipeline-http-kqueue ktor-cio-keel-nwconnection ktor-keel-nwconnection pipeline-http-nwconnection ktor-cio; do
                engines+=("native:${engine}|${NATIVE_BIN} --engine=${engine} --port=${PORT}")
            done
        fi
    elif [ "$(uname)" = "Linux" ]; then
        NATIVE_BIN="benchmark/build/bin/linuxX64/releaseExecutable/benchmark.kexe"
        if [ -f "$NATIVE_BIN" ]; then
            for engine in ktor-cio-keel-epoll ktor-keel-epoll pipeline-http-epoll ktor-cio-keel-io-uring ktor-keel-io-uring pipeline-http-io-uring ktor-cio; do
                engines+=("native:${engine}|${NATIVE_BIN} --engine=${engine} --port=${PORT}")
            done
        fi
    fi

    # JVM servers
    JVM_CP_FILE="benchmark/build/benchmark-classpath.txt"
    if [ -f "$JVM_CP_FILE" ]; then
        JVM_CP=$(cat "$JVM_CP_FILE")
        for engine in ktor-keel-nio pipeline-http-nio ktor-cio-keel-nio ktor-keel-netty ktor-cio-keel-netty pipeline-http-netty ktor-cio ktor-netty netty-raw spring vertx; do
            engines+=("jvm:${engine}|java -cp ${JVM_CP} io.github.fukusaka.keel.benchmark.JvmMainKt --engine=${engine} --port=${PORT}")
        done
    fi

    # JS (Node.js) server
    JS_BIN="benchmark/build/compileSync/js/main/productionExecutable/kotlin/keel-benchmark.js"
    if [ -f "$JS_BIN" ]; then
        engines+=("js:pipeline-http-nodejs|node ${JS_BIN} --engine=pipeline-http-nodejs --port=${PORT}")
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

# --- Collect results and write summary ---

OUTFILE="${RESULTS_DIR}/stream-all-${TIMESTAMP}.txt"

# Run all scenarios
for scenario in $SCENARIOS; do
    echo "=== Streaming scenario: ${scenario} ==="
    echo ""
    printf "  %-32s %12s  %-10s  %-10s\n" "Engine" "RPS" "p50" "p99"
    printf "  %-32s %12s  %-10s  %-10s\n" "--------------------------------" "------------" "----------" "----------"

    {
        echo "=== Streaming scenario: ${scenario} ==="
        printf "  %-32s %12s  %-10s  %-10s\n" "Engine" "RPS" "p50" "p99"
        printf "  %-32s %12s  %-10s  %-10s\n" "--------------------------------" "------------" "----------" "----------"
    } >> "$OUTFILE"

    while IFS= read -r entry; do
        display="${entry%%|*}"
        cmdstr="${entry#*|}"

        # Split command string into array
        read -ra cmd <<< "$cmdstr"

        row=$(BENCH_PORT="$PORT" ./benchmark/bench-stream-one.sh "$display" "$scenario" "${cmd[@]}" 2>/dev/null | tail -1)
        if [ -n "$row" ]; then
            # Parse pipe-separated: name|rps|p50|p99
            IFS='|' read -r rname rps rp50 rp99 <<< "$row"
            printf "  %-32s %12s  %-10s  %-10s\n" "$rname" "$rps" "$rp50" "$rp99"
            printf "  %-32s %12s  %-10s  %-10s\n" "$rname" "$rps" "$rp50" "$rp99" >> "$OUTFILE"
        else
            printf "  %-32s %s\n" "$display" "FAILED / SKIPPED"
            printf "  %-32s %s\n" "$display" "FAILED / SKIPPED" >> "$OUTFILE"
        fi
        sleep "$COOLDOWN"
    done < <(build_engine_list)

    echo ""
    echo "" >> "$OUTFILE"
done

echo "=== Done. Results: ${OUTFILE} ==="
