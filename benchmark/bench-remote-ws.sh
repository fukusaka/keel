#!/usr/bin/env bash
# bench-remote-ws.sh — WebSocket permessage-deflate (RFC 7692) real-network
# throughput benchmark for keel.
#
# permessage-deflate reduces wire bytes. On loopback the bandwidth is
# effectively unlimited, so compression only adds CPU cost and looks like a
# regression. The compression win shows only under a bandwidth cap on a real
# network link. This script therefore:
#
#   1. Starts a keel server on a remote host (same lifecycle pattern as
#      bench-remote.sh).
#   2. Applies a `tc tbf rate` bandwidth cap on the server interface (same
#      idea as bench-remote-slow.sh's rate-only tbf, no netem delay/loss).
#   3. Drives the Go `wsbench` client (which CAN negotiate permessage-deflate
#      via gorilla's Dialer.EnableCompression — k6 cannot) from a separate
#      client host, over the capped link, in the `deflate` scenario.
#
# Run it twice — once with BENCH_WS_COMPRESSION=true and once =false — to
# get the A/B: under the bandwidth cap, compression-on should win on
# msgs/sec.
#
# ## Docker fallback modes
#
# Two host capabilities the bench needs are often unavailable as native
# tools: a Go toolchain on the client host (to build `wsbench`) and
# passwordless `sudo tc` on the server host (to apply the bandwidth cap).
# Both fall back to Docker, mirroring bench-remote.sh's `BENCH_WRK_MODE`:
#
#   - wsbench: `BENCH_WSBENCH_MODE=docker` builds + runs `wsbench` inside a
#     `golang` image on the client host (no native Go toolchain needed).
#   - tc: `BENCH_TC_MODE=docker` applies the qdisc from a
#     `--privileged --network=host` container on the server host, which
#     shares the host network namespace so `tc` shapes the real interface
#     (no passwordless `sudo tc` needed).
#
# Both default to `auto` — native is probed first, Docker second. The
# Docker invocation itself is probed per host (`docker` vs `sudo -n docker`).
#
# Usage: ./benchmark/bench-remote-ws.sh <name> <command> [args...]
#
# Required environment variables:
#   BENCH_REMOTE_HOST    Server host (ssh target that runs <command>).
#   BENCH_CLIENT_HOST    wsbench client host (ssh target that drives load).
#   BENCH_TC_INTERFACE   Server interface to apply the tc tbf qdisc on
#                        (e.g. eth0).
#
# Optional environment variables:
#   BENCH_NETEM_RATE     tbf rate cap applied at the qdisc root
#                        (default: 50mbit). This is the bandwidth ceiling
#                        the A/B is measured against.
#   BENCH_TC_MODE        How to run `tc` on the server: "sudo" (passwordless
#                        `sudo -n tc`), "docker" (a `--privileged
#                        --network=host` container), or "auto" (default —
#                        probe sudo first, then docker).
#   BENCH_TC_IMAGE       Alpine-based image used in tc "docker" mode;
#                        `iproute2` is installed into it at run time
#                        (default: alpine:latest).
#   BENCH_WSBENCH_MODE   How to build/run wsbench on the client: "native"
#                        (a Go toolchain on PATH), "docker" (a `golang`
#                        image), or "auto" (default — probe native Go
#                        first, then docker).
#   BENCH_GOLANG_IMAGE   Image used in wsbench "docker" mode for both the
#                        `go build` and the run (default: golang:1.24).
#   BENCH_PORT           Server port (default: 18090). Also overridden by a
#                        --port=N token in <args>.
#   BENCH_SERVER_IP      IP or hostname the client uses in the ws:// URL;
#                        some client hosts cannot resolve mDNS hostnames, so
#                        a routable IP may be required (default:
#                        BENCH_REMOTE_HOST).
#   BENCH_WS_VUS         wsbench concurrent virtual users (default: 16).
#   BENCH_WS_DURATION    wsbench bench wall-clock duration (default: 15s).
#   BENCH_WS_BYTES       wsbench per-message payload size in bytes
#                        (default: 4096). The deflate scenario fills it with
#                        synthetic compressible text (~3:1 ratio).
#   BENCH_WS_COMPRESSION true | false — forwarded as `-compression=` to
#                        wsbench. true (default) negotiates permessage-
#                        deflate; false runs the uncompressed A/B leg.
#   BENCH_WS_PATH        WebSocket path (default: empty — wsbench picks
#                        /ws-deflate for the deflate scenario).
#   BENCH_REMOTE_WORKDIR Path to the keel checkout on the server host
#                        (default: ~/prj/keel-work/keel).
#   BENCH_CLIENT_WORKDIR Path on the client host where the wsbench sources
#                        are rsync'd and built (default:
#                        ~/prj/keel-work/keel-wsbench).
#   BENCH_SCHEME         ws | wss (default: ws). wss requires the server to
#                        serve TLS; the client skips cert verification.
#
# Output format (passed straight through from wsbench):
#   <name>|<msgs/sec>|<p50>|<p99>
#
# Cleanup: the tc qdisc on the server is removed via `trap EXIT` regardless
# of exit status. If this script is killed by SIGKILL (uncatchable), remove
# it manually with the same mode the run used (sudo or a privileged
# container): `tc qdisc del dev <iface> root`.
#
# Example (A/B over a 50 Mbit cap):
#   for c in true false; do
#     BENCH_REMOTE_HOST=bench.example BENCH_CLIENT_HOST=client.example \
#       BENCH_TC_INTERFACE=eth0 BENCH_NETEM_RATE=50mbit \
#       BENCH_WS_COMPRESSION=$c \
#       ./benchmark/bench-remote-ws.sh server-http-epoll-deflate-$c \
#         benchmark/build/bin/linuxX64/releaseExecutable/benchmark.kexe \
#         --engine=server-http-epoll --port=18090
#   done

set -uo pipefail

NAME="${1:?Usage: bench-remote-ws.sh <name> <command> [args...]}"
shift
if [ "$#" -lt 1 ]; then
    echo "Usage: bench-remote-ws.sh <name> <command> [args...]" >&2
    exit 1
fi

: "${BENCH_REMOTE_HOST:?BENCH_REMOTE_HOST is required (server host for ssh)}"
: "${BENCH_CLIENT_HOST:?BENCH_CLIENT_HOST is required (wsbench client host for ssh)}"
: "${BENCH_TC_INTERFACE:?BENCH_TC_INTERFACE is required (server iface for tc tbf qdisc)}"

REMOTE_HOST="$BENCH_REMOTE_HOST"
CLIENT_HOST="$BENCH_CLIENT_HOST"
TC_IFACE="$BENCH_TC_INTERFACE"
NETEM_RATE="${BENCH_NETEM_RATE:-50mbit}"
WORKDIR="${BENCH_REMOTE_WORKDIR:-~/prj/keel-work/keel}"
CLIENT_WORKDIR="${BENCH_CLIENT_WORKDIR:-~/prj/keel-work/keel-wsbench}"
SERVER_IP="${BENCH_SERVER_IP:-$REMOTE_HOST}"

TC_MODE="${BENCH_TC_MODE:-auto}"
TC_IMAGE="${BENCH_TC_IMAGE:-alpine:latest}"
WSBENCH_MODE="${BENCH_WSBENCH_MODE:-auto}"
GOLANG_IMAGE="${BENCH_GOLANG_IMAGE:-golang:1.24}"

PORT=${BENCH_PORT:-18090}
WS_VUS=${BENCH_WS_VUS:-16}
WS_DURATION=${BENCH_WS_DURATION:-15s}
WS_BYTES=${BENCH_WS_BYTES:-4096}
WS_COMPRESSION=${BENCH_WS_COMPRESSION:-true}
WS_PATH="${BENCH_WS_PATH:-}"
SCHEME=${BENCH_SCHEME:-ws}
TC_HANDLE="1:"
READY_TIMEOUT=${BENCH_READY_TIMEOUT:-60}

# Docker invocation per host, resolved lazily by resolve_docker(): either
# "docker", "sudo -n docker", or "" when Docker is unavailable.
SERVER_DOCKER=""
CLIENT_DOCKER=""

case "$SCHEME" in
    ws|wss) ;;
    *)
        echo "ERROR: BENCH_SCHEME must be 'ws' or 'wss' (got '$SCHEME')" >&2
        exit 2
        ;;
esac

case "$TC_MODE" in
    sudo|docker|auto) ;;
    *)
        echo "ERROR: BENCH_TC_MODE must be 'sudo', 'docker' or 'auto' (got '$TC_MODE')" >&2
        exit 2
        ;;
esac

case "$WSBENCH_MODE" in
    native|docker|auto) ;;
    *)
        echo "ERROR: BENCH_WSBENCH_MODE must be 'native', 'docker' or 'auto' (got '$WSBENCH_MODE')" >&2
        exit 2
        ;;
esac

# Allow --port=N in the server args to override BENCH_PORT.
for arg in "$@"; do
    case "$arg" in
        --port=*) PORT="${arg#--port=}" ;;
    esac
done

LOG_PATH="/tmp/bench-remote-ws-${NAME}.log"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- Capability resolution ---

# Prints the working Docker invocation on $1 — "docker" when the ssh user
# can run it directly, "sudo -n docker" when only passwordless sudo works,
# or "" when neither does.
resolve_docker() {
    local host="$1"
    if ssh -n "$host" 'docker ps >/dev/null 2>&1'; then
        echo "docker"
    elif ssh -n "$host" 'sudo -n docker ps >/dev/null 2>&1'; then
        echo "sudo -n docker"
    else
        echo ""
    fi
}

# Resolves TC_MODE (auto → sudo|docker) and WSBENCH_MODE (auto → native|docker),
# probing host capabilities. Populates SERVER_DOCKER / CLIENT_DOCKER when a
# docker mode is selected. Exits with a clear error when neither path works.
resolve_modes() {
    if [ "$TC_MODE" = auto ]; then
        if ssh -n "$REMOTE_HOST" "sudo -n tc qdisc show dev ${TC_IFACE} >/dev/null 2>&1"; then
            TC_MODE=sudo
        else
            SERVER_DOCKER="$(resolve_docker "$REMOTE_HOST")"
            if [ -n "$SERVER_DOCKER" ]; then
                TC_MODE=docker
            else
                echo "ERROR: cannot apply tc on ${REMOTE_HOST} — neither passwordless 'sudo tc'" >&2
                echo "       nor Docker is available. Configure one, or set BENCH_TC_MODE." >&2
                exit 1
            fi
        fi
    elif [ "$TC_MODE" = docker ]; then
        SERVER_DOCKER="$(resolve_docker "$REMOTE_HOST")"
        [ -n "$SERVER_DOCKER" ] || { echo "ERROR: BENCH_TC_MODE=docker but no Docker on ${REMOTE_HOST}" >&2; exit 1; }
    fi

    if [ "$WSBENCH_MODE" = auto ]; then
        if ssh -n "$CLIENT_HOST" 'command -v go >/dev/null 2>&1'; then
            WSBENCH_MODE=native
        else
            CLIENT_DOCKER="$(resolve_docker "$CLIENT_HOST")"
            if [ -n "$CLIENT_DOCKER" ]; then
                WSBENCH_MODE=docker
            else
                echo "ERROR: cannot build wsbench on ${CLIENT_HOST} — neither a Go toolchain" >&2
                echo "       nor Docker is available. Install one, or set BENCH_WSBENCH_MODE." >&2
                exit 1
            fi
        fi
    elif [ "$WSBENCH_MODE" = docker ]; then
        CLIENT_DOCKER="$(resolve_docker "$CLIENT_HOST")"
        [ -n "$CLIENT_DOCKER" ] || { echo "ERROR: BENCH_WSBENCH_MODE=docker but no Docker on ${CLIENT_HOST}" >&2; exit 1; }
    fi

    echo "[bench-remote-ws] tc mode: ${TC_MODE}, wsbench mode: ${WSBENCH_MODE}" >&2
}

# --- tc tbf qdisc lifecycle on the server ---

# Runs `tc $*` on the server. In sudo mode via `sudo -n tc`; in docker mode
# inside a --privileged --network=host container that shares the host
# network namespace, so the qdisc lands on the real host interface.
# iproute2 is installed into the (Alpine-based) image at run time.
run_tc() {
    local tc_args="$*"
    case "$TC_MODE" in
        sudo)
            ssh -n "$REMOTE_HOST" "sudo -n tc ${tc_args}"
            ;;
        docker)
            ssh -n "$REMOTE_HOST" \
                "${SERVER_DOCKER} run --rm --privileged --network=host ${TC_IMAGE} \
                 sh -c 'apk add -q --no-cache iproute2 >/dev/null 2>&1 && tc ${tc_args}'"
            ;;
    esac
}

apply_tc() {
    # rate-only tbf at root — netem delay/loss are not needed here, the
    # bandwidth cap alone is what makes permessage-deflate's wire-byte
    # reduction observable.
    run_tc "qdisc add dev ${TC_IFACE} root handle ${TC_HANDLE} tbf rate ${NETEM_RATE} burst 32kbit latency 400ms" \
        || { echo "ERROR: tc qdisc add tbf failed on ${REMOTE_HOST}:${TC_IFACE}" >&2; exit 1; }
    echo "[bench-remote-ws] tc tbf rate ${NETEM_RATE} applied on ${REMOTE_HOST}:${TC_IFACE} (${TC_MODE} mode)" >&2
}

cleanup_tc() {
    # Idempotent — `del root` removes whatever is at root, no-op if nothing.
    run_tc "qdisc del dev ${TC_IFACE} root" >/dev/null 2>&1 || true
    echo "[bench-remote-ws] tc qdisc cleanup done on ${REMOTE_HOST}:${TC_IFACE}" >&2
}

# --- Server lifecycle on the remote host ---

kill_server() {
    # Kill by port binding. `fuser -k` prints matched PIDs on stdout — the
    # stdout redirect keeps them out of the parsed benchmark output line.
    ssh -n "$REMOTE_HOST" "fuser -k ${PORT}/tcp >/dev/null 2>&1 || true"
    sleep 1
}

start_server() {
    # Detach pattern: `cd && { nohup ... & disown; }`. The brace group
    # scopes `&` to the single nohup command, so no extra subshell inherits
    # ssh's stdio fds and ssh returns once the brace group finishes.
    # Writing this without braces parses as `(cd && nohup ...) & disown`,
    # forking a subshell that holds the ssh fds and blocks ssh until the
    # server process exits.
    local quoted_cmd
    printf -v quoted_cmd '%q ' "$@"
    ssh -n "$REMOTE_HOST" "cd ${WORKDIR} && { nohup ${quoted_cmd}>${LOG_PATH} 2>&1 </dev/null & disown; }"
}

wait_for_ready() {
    local elapsed=0
    while [ "$elapsed" -lt "$READY_TIMEOUT" ]; do
        if ssh -n "$REMOTE_HOST" "ss -lnt | grep -q ':${PORT}\b'"; then
            return 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    return 1
}

# --- wsbench build + run on the client host ---

build_wsbench() {
    # wsbench is platform-specific and .gitignore'd, so it cannot be
    # transferred prebuilt. rsync the Go sources to the client host and
    # build there — natively when a Go toolchain is present, otherwise
    # inside a `golang` image.
    ssh -n "$CLIENT_HOST" "mkdir -p ${CLIENT_WORKDIR}" \
        || { echo "ERROR: cannot create ${CLIENT_WORKDIR} on ${CLIENT_HOST}" >&2; exit 1; }
    rsync -az \
        "${SCRIPT_DIR}/wsbench/go.mod" \
        "${SCRIPT_DIR}/wsbench/go.sum" \
        "${SCRIPT_DIR}"/wsbench/*.go \
        "${CLIENT_HOST}:${CLIENT_WORKDIR}/" \
        || { echo "ERROR: rsync of wsbench sources to ${CLIENT_HOST} failed" >&2; exit 1; }

    case "$WSBENCH_MODE" in
        native)
            ssh -n "$CLIENT_HOST" "cd ${CLIENT_WORKDIR} && go build -o wsbench ." \
                || { echo "ERROR: 'go build' of wsbench on ${CLIENT_HOST} failed" >&2; exit 1; }
            ;;
        docker)
            # CGO_ENABLED=0 yields a static binary; the bind mount lands it
            # back in CLIENT_WORKDIR for the run step.
            ssh -n "$CLIENT_HOST" \
                "${CLIENT_DOCKER} run --rm -e CGO_ENABLED=0 -v ${CLIENT_WORKDIR}:/src -w /src \
                 ${GOLANG_IMAGE} go build -o wsbench ." \
                || { echo "ERROR: dockerized 'go build' of wsbench on ${CLIENT_HOST} failed" >&2; exit 1; }
            ;;
    esac
    echo "[bench-remote-ws] wsbench built on ${CLIENT_HOST}:${CLIENT_WORKDIR} (${WSBENCH_MODE} mode)" >&2
}

run_wsbench() {
    # `printf %q` escapes each token so it survives the remote shell
    # re-parse intact (matches the bench-remote.sh idiom).
    local quoted
    printf -v quoted '%q ' "$@"
    case "$WSBENCH_MODE" in
        native)
            ssh -n "$CLIENT_HOST" "cd ${CLIENT_WORKDIR} && ./wsbench ${quoted}"
            ;;
        docker)
            # --network=host so the container reaches the capped server link.
            ssh -n "$CLIENT_HOST" \
                "${CLIENT_DOCKER} run --rm --network=host -v ${CLIENT_WORKDIR}:/w -w /w \
                 ${GOLANG_IMAGE} ./wsbench ${quoted}"
            ;;
    esac
}

# --- Main ---

resolve_modes
trap cleanup_tc EXIT INT TERM

apply_tc
build_wsbench

kill_server
start_server "$@"

if ! wait_for_ready; then
    echo "$NAME|FAILED|-|-" >&2
    ssh -n "$REMOTE_HOST" "tail -5 ${LOG_PATH}" >&2 || true
    kill_server
    exit 1
fi

WSBENCH_ARGS=(
    "-name=${NAME}"
    "-scenario=deflate"
    "-scheme=${SCHEME}"
    "-host=${SERVER_IP}"
    "-port=${PORT}"
    "-vus=${WS_VUS}"
    "-duration=${WS_DURATION}"
    "-bytes=${WS_BYTES}"
    "-compression=${WS_COMPRESSION}"
)
if [ -n "$WS_PATH" ]; then
    WSBENCH_ARGS+=("-path=${WS_PATH}")
fi

RESULT=$(run_wsbench "${WSBENCH_ARGS[@]}" 2>&1)
EXIT_CODE=$?

kill_server

# wsbench already emits the canonical `<name>|<rps>|<p50>|<p99>` row, so
# pull just the line starting with the engine name and pass it through.
ROW=$(printf '%s' "$RESULT" | grep -E "^${NAME}\|" | tail -1)
if [ -z "$ROW" ]; then
    echo "$NAME|FAILED|-|-" >&2
    printf '%s\n' "$RESULT" >&2
    exit "${EXIT_CODE:-1}"
fi
echo "$ROW"
