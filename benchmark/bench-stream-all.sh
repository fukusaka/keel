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
cd "$(dirname "$0")/.." || exit 1

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
    local scenario="$1"
    local engines=()

    # JS (Node.js) server runs first to avoid macOS ephemeral port exhaustion.
    # Each preceding engine consumes ~5K-7K ephemeral ports during its 50-VU
    # 15s bench, and macOS holds them in TIME_WAIT for 2*MSL = 30s (sysctl
    # `net.inet.tcp.msl` default 15000 ms). With only a 2s inter-engine
    # cooldown, the macOS ephemeral pool (49152-65535 = 16,384 ports) drains
    # within ~3-4 engines and stays at saturation for the rest of the chain.
    # NOTE: this is *client-side ephemeral* port exhaustion, distinct from the
    # *server listening* port. The latter is now a per-engine port (see the run
    # loop below) so no engine binds a port a prior engine left in TIME_WAIT.
    # Multi-threaded engines (kqueue / nio / netty / nwconnection / Phase 2
    # natives) absorb the ephemeral-port setup pressure transparently;
    # Node.js's single-threaded libuv event loop is materially slower at
    # completing a WebSocket upgrade on the loopback path, so it is the
    # canary that surfaces the saturation as `k6 ws-large status 101 0%`
    # (every WS handshake completes then immediately closes — k6's onclose
    # fires with `opened === false`). Same class of macOS-specific bench
    # symptom documented in nodejs/node#32337. Running the JS engine first
    # is a fixed-cost workaround that costs nothing for the other engines
    # (they handle port saturation transparently regardless of position).
    JS_BIN="benchmark/build/compileSync/js/main/productionExecutable/kotlin/keel-benchmark.js"
    if [ -f "$JS_BIN" ]; then
        engines+=("js:pipeline-http-nodejs|node ${JS_BIN} --engine=pipeline-http-nodejs --port=${PORT}")
        engines+=("js:server-http-nodejs|node ${JS_BIN} --engine=server-http-nodejs --port=${PORT}")
    else
        # Warn — instead of silent-skipping — when the JS bin is missing, since
        # a `:benchmark:compileProductionExecutableKotlinJs` build omission was
        # the exact reason the 2026-06-19 fresh baseline sweep produced zero JS
        # engine rows.
        echo "WARN: JS engine binary not found at ${JS_BIN}; pipeline-http-nodejs / server-http-nodejs will be skipped." >&2
        echo "      Run: ./gradlew -Pbenchmark :benchmark:compileProductionExecutableKotlinJs" >&2
    fi

    # Cross-language reference servers
    case "$scenario" in
        multipart|method-mix|path-param)
            ;;
        *)
            # swift-bench is macOS-only — Sources/main.swift uses
            # Network.framework which has no Linux counterpart. Gate the entry
            # on the host OS rather than letting an empty-file check sort it
            # out (a stale macOS-cross-built binary on Linux would otherwise
            # be picked up and READY_TIMEOUT_7 at run time).
            local cross_pairs=(
                "rust-bench|benchmark/rust-bench/target/release/rust-bench --port=${PORT}"
                "go-bench|benchmark/go-bench/go-bench --port=${PORT}"
                "zig-bench|benchmark/zig-bench/zig-out/bin/zig-bench --port=${PORT}"
            )
            if [ "$(uname)" = "Darwin" ]; then
                cross_pairs+=("swift-bench|benchmark/swift-bench/.build/release/swift-bench --port=${PORT}")
            fi
            for pair in "${cross_pairs[@]}"; do
                local display="${pair%%|*}"
                local cmd="${pair#*|}"
                local binary="${cmd%% *}"
                if [ -f "$binary" ]; then
                    engines+=("${display}|${cmd}")
                fi
            done
            ;;
    esac

    # Kotlin/Native servers
    if [ "$(uname)" = "Darwin" ]; then
        ARCH=$(uname -m)
        if [ "$ARCH" = "arm64" ]; then
            NATIVE_BIN="benchmark/build/bin/macosArm64/releaseExecutable/benchmark.kexe"
        else
            NATIVE_BIN="benchmark/build/bin/macosX64/releaseExecutable/benchmark.kexe"
        fi
        if [ -f "$NATIVE_BIN" ]; then
            for engine in ktor-cio-keel-kqueue ktor-keel-kqueue pipeline-http-kqueue server-http-kqueue ktor-cio-keel-nwconnection ktor-keel-nwconnection pipeline-http-nwconnection server-http-nwconnection ktor-cio; do
                engines+=("native:${engine}|${NATIVE_BIN} --engine=${engine} --port=${PORT}")
            done
        fi
    elif [ "$(uname)" = "Linux" ]; then
        NATIVE_BIN="benchmark/build/bin/linuxX64/releaseExecutable/benchmark.kexe"
        if [ -f "$NATIVE_BIN" ]; then
            for engine in ktor-cio-keel-epoll ktor-keel-epoll pipeline-http-epoll server-http-epoll ktor-cio-keel-io-uring ktor-keel-io-uring pipeline-http-io-uring server-http-io-uring ktor-cio; do
                engines+=("native:${engine}|${NATIVE_BIN} --engine=${engine} --port=${PORT}")
            done
        fi
    fi

    # JVM servers
    JVM_CP_FILE="benchmark/build/benchmark-classpath.txt"
    if [ -f "$JVM_CP_FILE" ]; then
        # Resolve writeClasspath placeholders against this host before
        # launching any JVM engine — see benchmark/bench-jvm-cp.sh.
        if JVM_CP=$(./benchmark/bench-jvm-cp.sh resolve 2>/dev/null); then
            for engine in ktor-keel-nio pipeline-http-nio server-http-nio ktor-cio-keel-nio ktor-keel-netty ktor-cio-keel-netty pipeline-http-netty server-http-netty ktor-cio ktor-netty netty-raw spring vertx; do
                engines+=("jvm:${engine}|java -cp ${JVM_CP} io.github.fukusaka.keel.benchmark.JvmMainKt --engine=${engine} --port=${PORT}")
            done
        else
            echo "JVM_CP_INVALID: skipping JVM engines" >&2
            echo "  hint: rebuild on this host: ./gradlew -Pbenchmark :benchmark:writeClasspath" >&2
        fi
    fi

    # JS (Node.js) server is appended at the top of build_engine_list above
    # (see the ephemeral-port-exhaustion comment there). Do not also append
    # it here.

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

    # Give each engine a distinct listening port (base + index). The NWConnection
    # engine cannot rebind a port a prior engine left in TIME_WAIT (Apple Radar
    # FB8658821); sharing one port across the sequential sweep made the first
    # nwconnection server fail its READY check with EADDRINUSE. The HTTP sweep
    # (bench-all.sh) already increments the port per engine for the same reason.
    engine_index=0
    while IFS= read -r entry; do
        display="${entry%%|*}"
        cmdstr="${entry#*|}"

        # Rewrite the server's --port to a per-engine port (base + index) so no
        # two engines in the sweep share a listening port (TIME_WAIT note above).
        engine_port=$((PORT + engine_index))
        cmdstr="${cmdstr//--port=$PORT/--port=$engine_port}"

        # Split command string into array
        read -ra cmd <<< "$cmdstr"

        row=$(./benchmark/bench-stream-one.sh "$display" "$scenario" "${cmd[@]}" 2>/dev/null | tail -1)
        if [ -n "$row" ]; then
            # Parse pipe-separated: name|rps|p50|p99[|temp=...]. The trailing
            # `rest` catch-all keeps p99 clean when BENCH_TEMP_CAPTURE adds a
            # `temp=...` field (without it, a 4-var read would absorb temp into
            # p99). `rest` is surfaced after p99 so the sweep table still shows
            # the per-engine temperature when capture is enabled.
            IFS='|' read -r rname rps rp50 rp99 rest <<< "$row"
            printf "  %-32s %12s  %-10s  %-10s%s\n" "$rname" "$rps" "$rp50" "$rp99" "${rest:+  $rest}"
            printf "  %-32s %12s  %-10s  %-10s%s\n" "$rname" "$rps" "$rp50" "$rp99" "${rest:+  $rest}" >> "$OUTFILE"
        else
            printf "  %-32s %s\n" "$display" "FAILED / SKIPPED"
            printf "  %-32s %s\n" "$display" "FAILED / SKIPPED" >> "$OUTFILE"
        fi
        engine_index=$((engine_index + 1))
        sleep "$COOLDOWN"
    done < <(build_engine_list "$scenario")

    echo ""
    echo "" >> "$OUTFILE"
done

echo "=== Done. Results: ${OUTFILE} ==="
