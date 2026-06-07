#!/usr/bin/env bash
# Real-network multi-engine batch benchmark for keel servers.
#
# Drives every keel engine that has a buildable binary on the remote
# host through `bench-remote.sh` and aggregates the results into a single
# table (analogous to `bench-keel.sh` but over a real network link rather
# than loopback). Designed to surface the cumulative L7 alloc-reduction
# effect that loopback benchmarks tend to hide — when wire is the
# bottleneck on loopback, lower per-request allocation translates into
# GC headroom rather than throughput, which the alloc-only bench
# already captures; on a real link with multiple connections, the same
# reduction can convert to throughput.
#
# Usage: ./benchmark/bench-remote-keel.sh
#
# Required environment variables (forwarded to `bench-remote.sh`):
#   BENCH_REMOTE_HOST    Server host
#   BENCH_CLIENT_HOST    wrk client host (must be distinct from server)
#
# Optional environment variables (forwarded to `bench-remote.sh`):
#   BENCH_REMOTE_WORKDIR (default: ~/prj/keel-work/keel)
#   BENCH_SERVER_IP      (default: BENCH_REMOTE_HOST)
#   BENCH_WRK_MODE       native|docker|auto (default: auto)
#   BENCH_WRK_DOCKER_IMAGE  (default: williamyeh/wrk:latest)
#   BENCH_PORT           (default: 18090)
#   BENCH_ENDPOINT       (default: /hello)
#   BENCH_RUNS           (default: 1; median when >1)
#   BENCH_COOLDOWN       (default: 2 s between engines + between runs)
#   BENCH_WRK_THREADS    (default: 4)
#   BENCH_WRK_CONNS      (default: 100)
#   BENCH_WRK_DURATION   (default: 10s)
#   BENCH_WARMUP         (default: 3s)
#   BENCH_SCHEME         (default: http)
#   BENCH_TLS            (default: empty; "openssl" / "jsse" / etc.)
#   BENCH_GC_CAPTURE     (default: 0; set to 1 for JVM GC stats)
#   BENCH_SHUFFLE        (default: false; randomize engine order to
#                         remove order-dependent bias)
#   BENCH_HOST_LABEL     (default: short hostname of BENCH_REMOTE_HOST)
#   BENCH_REMOTE_OS      (default: probed via `uname` on BENCH_REMOTE_HOST.
#                         "Linux" / "Darwin")
#
# Engine list:
#   Native (only when BENCH_REMOTE_OS = Linux): ktor-keel-epoll,
#     pipeline-http-epoll, ktor-cio-keel-epoll, ktor-keel-io-uring,
#     pipeline-http-io-uring, ktor-cio-keel-io-uring, ktor-cio.
#   JVM (always): ktor-keel-nio, pipeline-http-nio, server-http-nio, ktor-cio-keel-nio,
#     ktor-keel-netty, ktor-cio-keel-netty, pipeline-http-netty, server-http-netty, ktor-cio.
#   JS (Node.js): pipeline-http-nodejs, only when
#     `benchmark/build/compileSync/js/main/productionExecutable/kotlin/keel-benchmark.js`
#     is present on the remote host.
#
# The JVM classpath is read from `benchmark/build/benchmark-classpath.txt`
# on the REMOTE host (not the local one) — that file contains absolute
# paths produced by the remote `:benchmark:writeClasspath` task and is
# the only one whose paths are valid on the remote filesystem.
#
# Output: same per-engine line as bench-remote.sh; a header row labels
# the columns. With BENCH_GC_CAPTURE=1 the JVM engines get a trailing
# `GC:<alloc>|<ygc>|<ygc_ms>|<fgc>|<fgc_ms>|<gc_pct>` block; Native
# engines get no GC block.

set -uo pipefail
cd "$(dirname "$0")/.."

: "${BENCH_REMOTE_HOST:?BENCH_REMOTE_HOST is required (server host for ssh)}"
: "${BENCH_CLIENT_HOST:?BENCH_CLIENT_HOST is required (wrk client host for ssh)}"

REMOTE_HOST="$BENCH_REMOTE_HOST"
WORKDIR="${BENCH_REMOTE_WORKDIR:-~/prj/keel-work/keel}"
SCHEME=${BENCH_SCHEME:-http}
ENDPOINT="${BENCH_ENDPOINT:-/hello}"
PORT=${BENCH_PORT:-18090}
RUNS=${BENCH_RUNS:-1}
SHUFFLE=${BENCH_SHUFFLE:-false}
WRK_THREADS=${BENCH_WRK_THREADS:-4}
WRK_CONNS=${BENCH_WRK_CONNS:-100}
WRK_DURATION=${BENCH_WRK_DURATION:-10s}
GC_CAPTURE=${BENCH_GC_CAPTURE:-0}
TLS_BACKEND="${BENCH_TLS:-}"

# Probe remote OS once so we know which Native binaries exist.
REMOTE_OS="${BENCH_REMOTE_OS:-$(ssh -n "$REMOTE_HOST" 'uname' 2>/dev/null || echo unknown)}"

# Results directory mirrors bench-keel.sh layout.
HOST_LABEL="${BENCH_HOST_LABEL:-$(ssh -n "$REMOTE_HOST" 'hostname -s' 2>/dev/null || echo remote)}"
RESULTS_DIR="benchmark/results/${HOST_LABEL}"
mkdir -p "$RESULTS_DIR"

# --- Engine list builder ---
#
# Each entry: "type:display:engine:binary_or_marker". For JVM entries the
# binary slot is unused (we resolve the classpath on the remote at call
# time). Native binaries are validated remotely with `ssh test -f` so
# missing builds skip cleanly instead of failing the whole batch.

remote_file_exists() {
    ssh -n "$REMOTE_HOST" "test -f ${WORKDIR}/$1" 2>/dev/null
}

build_engine_list() {
    local engines=()

    case "$REMOTE_OS" in
        Linux)
            local nbin="benchmark/build/bin/linuxX64/releaseExecutable/benchmark.kexe"
            if remote_file_exists "$nbin"; then
                for engine in \
                    ktor-keel-epoll \
                    pipeline-http-epoll \
                    ktor-cio-keel-epoll \
                    ktor-keel-io-uring \
                    pipeline-http-io-uring \
                    ktor-cio-keel-io-uring \
                    ktor-cio; do
                    engines+=("kn-engine:native:${engine}:${nbin}")
                done
            fi
            ;;
        Darwin)
            # macOS server-side is unusual for real-network bench but
            # supported for completeness. Match bench-keel.sh's macOS
            # engine list.
            local nbin="benchmark/build/bin/macosArm64/releaseExecutable/benchmark.kexe"
            if remote_file_exists "$nbin"; then
                for engine in \
                    ktor-keel-kqueue \
                    pipeline-http-kqueue \
                    server-http-kqueue \
                    ktor-cio-keel-kqueue \
                    ktor-keel-nwconnection \
                    ktor-cio-keel-nwconnection \
                    pipeline-http-nwconnection \
                    server-http-nwconnection \
                    ktor-cio; do
                    engines+=("kn-engine:native:${engine}:${nbin}")
                done
            fi
            ;;
    esac

    if remote_file_exists "benchmark/build/benchmark-classpath.txt"; then
        for engine in \
            ktor-keel-nio \
            pipeline-http-nio \
            server-http-nio \
            ktor-cio-keel-nio \
            ktor-keel-netty \
            ktor-cio-keel-netty \
            pipeline-http-netty \
            server-http-netty \
            ktor-cio; do
            engines+=("jvm-engine:jvm:${engine}")
        done
    fi

    local jsbin="benchmark/build/compileSync/js/main/productionExecutable/kotlin/keel-benchmark.js"
    if remote_file_exists "$jsbin"; then
        engines+=("js-engine:js:pipeline-http-nodejs:${jsbin}")
    fi

    if [ "$SHUFFLE" = "true" ]; then
        local shuffled
        shuffled=$(printf '%s\n' "${engines[@]}" | sort -R)
        engines=()
        while IFS= read -r line; do
            engines+=("$line")
        done <<<"$shuffled"
    fi

    printf '%s\n' "${engines[@]}"
}

# Resolve the JVM classpath on the REMOTE host. The classpath file is anchored
# to ${REPO_ROOT} / ${GRADLE_USER_HOME} placeholders by :benchmark:writeClasspath
# so the file itself is portable, but the substitution must happen on the host
# whose paths it should resolve to. We invoke benchmark/bench-jvm-cp.sh remotely
# (cd'd to $WORKDIR), which both substitutes and sanity-probes the entries.
remote_jvm_classpath() {
    ssh -n "$REMOTE_HOST" "cd ${WORKDIR} && bash benchmark/bench-jvm-cp.sh resolve 2>/dev/null" || true
}

# --- Main ---

TLS_ARGS=()
if [ -n "$TLS_BACKEND" ]; then
    TLS_ARGS+=("--tls=${TLS_BACKEND}")
fi

cat <<HEADER
=== keel real-network Benchmark: ${SCHEME}${ENDPOINT} (${WRK_THREADS}t/${WRK_CONNS}c/${WRK_DURATION}) ===
   server  = ${REMOTE_HOST} (${REMOTE_OS})
   client  = ${BENCH_CLIENT_HOST}
   runs    = ${RUNS}   shuffle = ${SHUFFLE}   gc capture = ${GC_CAPTURE}
HEADER

if [ "$GC_CAPTURE" = 1 ]; then
    printf '   %-32s %12s   %-9s  %-9s   %-22s\n' \
        "Server" "Req/sec" "p50" "p99" "GC (alloc/ygc/fgc/%)"
    printf '   %-32s %12s   %-9s  %-9s   %-22s\n' \
        "--------------------------------" "------------" "---------" "---------" "----------------------"
else
    printf '   %-32s %12s   %-9s  %-9s\n' \
        "Server" "Req/sec" "p50" "p99"
    printf '   %-32s %12s   %-9s  %-9s\n' \
        "--------------------------------" "------------" "---------" "---------"
fi

# Resolve JVM classpath once (might be empty if remote didn't run writeClasspath).
JVM_CP=$(remote_jvm_classpath)

run_one() {
    local label="$1"
    shift
    local out
    # bench-remote.sh inherits all BENCH_* env vars so no need to re-pass them.
    out=$(./benchmark/bench-remote.sh "$label" "$@" 2>&1)
    local exit_code=$?
    # bench-remote.sh's result line is the LAST line of stdout (it streams
    # ssh / wrk noise to stderr, but ssh sometimes leaks into stdout on
    # some sshd configs). Pick the last `name|...|` line to be safe.
    local result
    result=$(echo "$out" | grep -E "^${label}\|" | tail -1)
    if [ -z "$result" ]; then
        echo "   FAILED: $label  (exit=${exit_code})" >&2
        echo "$out" | tail -10 >&2
        return
    fi
    # Reformat to the table layout. Columns: name | rps | p50 | p99
    # [| [all_rps] ] [| GC:... ]
    local name rps p50 p99 trailing
    IFS='|' read -r name rps p50 p99 trailing <<<"$result|"
    # The trailing field absorbs everything after p99 (all_rps array
    # and/or GC summary); strip the synthetic trailing pipe we added.
    trailing="${trailing%|}"
    if [ "$GC_CAPTURE" = 1 ]; then
        # Pull just the GC:... block out of the trailing remainder
        # (it follows an optional `[runs]` token).
        local gc_block=""
        if [[ "$trailing" == *"GC:"* ]]; then
            gc_block="${trailing#*GC:}"
            gc_block="GC:${gc_block}"
        fi
        printf '   %-32s %12s   %-9s  %-9s   %s\n' "$name" "$rps" "$p50" "$p99" "$gc_block"
    else
        printf '   %-32s %12s   %-9s  %-9s\n' "$name" "$rps" "$p50" "$p99"
    fi
}

while IFS= read -r entry; do
    [ -z "$entry" ] && continue
    type="${entry%%:*}"
    rest="${entry#*:}"
    case "$type" in
        kn-engine)
            display="${rest%%:*}"
            rest2="${rest#*:}"
            engine="${rest2%%:*}"
            binary="${rest2#*:}"
            run_one "${display}:${engine}" \
                "$binary" --engine="${engine}" --port="${PORT}" "${TLS_ARGS[@]}"
            ;;
        jvm-engine)
            display="${rest%%:*}"
            engine="${rest#*:}"
            if [ -n "$JVM_CP" ]; then
                run_one "${display}:${engine}" \
                    java -cp "$JVM_CP" io.github.fukusaka.keel.benchmark.JvmMainKt \
                    --engine="${engine}" --port="${PORT}" "${TLS_ARGS[@]}"
            else
                echo "   SKIP: jvm:${engine}  (remote classpath file missing — run ./gradlew -Pbenchmark :benchmark:writeClasspath on $REMOTE_HOST)" >&2
            fi
            ;;
        js-engine)
            display="${rest%%:*}"
            rest2="${rest#*:}"
            engine="${rest2%%:*}"
            binary="${rest2#*:}"
            run_one "${display}:${engine}" \
                node "$binary" --engine="${engine}" --port="${PORT}" "${TLS_ARGS[@]}"
            ;;
    esac
done < <(build_engine_list)

echo ""
echo "=== Done ==="
