#!/usr/bin/env bash
# bench-client-macro.sh — macro (real-NIC, two-host) client bench tier.
#
# The client (SUT = the bench-client harness) runs HERE; the fixture (rust-bench)
# runs on a SEPARATE host over the real network. This is the inverse of
# bench-remote.sh (which puts the SERVER under test). Real RTT + no shared CPU
# make absolute latency / throughput meaningful — loopback contaminates both
# (client and fixture share the CPU) and hides the RTT that bounds real
# throughput. Pair this with BENCH_CLIENT_MODE=open: at real ms-scale RTT the
# open-loop pacing jitter is negligible and the CO-corrected tail is trustworthy.
#
# Manages the remote fixture lifecycle over ssh + optional tc netem RTT, then
# drives bench-client.sh with BENCH_CLIENT_TARGET pointing at the remote fixture.
#
# Env (no internal hosts baked in — set them at call time):
#   BENCH_SERVER_SSH     ssh target running the fixture (required)
#   BENCH_SERVER_IP      IP the client connects to; a routable LAN IP reachable
#                        from here, NOT the server's loopback (required)
#   BENCH_SERVER_WORKDIR keel dir on the server (default ~/prj/keel-work/keel)
#   BENCH_FIXTURE_PORT   fixture port (default 18850)
#   BENCH_NETEM_IFACE    server NIC for tc netem (required only with NETEM_DELAY)
#   BENCH_NETEM_DELAY    e.g. "10ms" or "10ms 2ms" (jitter); needs passwordless sudo
#   + bench-client.sh env (BENCH_CLIENT_TYPES / _CONNS / _ENDPOINT / _MODE / _RATE,
#     BENCH_RUNS). Native reference binaries must exist on the client host.
set -euo pipefail

cd "$(dirname "$0")/.."

SSH="${BENCH_SERVER_SSH:?set BENCH_SERVER_SSH to the fixture ssh host}"
IP="${BENCH_SERVER_IP:?set BENCH_SERVER_IP to a client-reachable server IP}"
WORKDIR="${BENCH_SERVER_WORKDIR:-~/prj/keel-work/keel}"
PORT="${BENCH_FIXTURE_PORT:-18850}"
NETEM_IFACE="${BENCH_NETEM_IFACE:-}"
NETEM_DELAY="${BENCH_NETEM_DELAY:-}"

BIN="$WORKDIR/benchmark/rust-bench/target/release/rust-bench"

# Reachability pre-flight: the client must route to the server IP (a common
# footgun is picking a server IP on a subnet the client cannot reach).
ping -c1 -t5 "$IP" >/dev/null 2>&1 || {
  echo "cannot reach $IP from the client host — check BENCH_SERVER_IP / routing" >&2
  exit 1
}

cleanup() {
  if [ -n "$NETEM_DELAY" ] && [ -n "$NETEM_IFACE" ]; then
    ssh "$SSH" "sudo tc qdisc del dev $NETEM_IFACE root 2>/dev/null || true"
  fi
  ssh "$SSH" "pkill -f 'rust-bench --port=$PORT' 2>/dev/null || true"
}
trap cleanup EXIT INT TERM

# Ensure the fixture binary exists on the server (build only the server bin).
ssh "$SSH" "[ -x $BIN ] || ( cd $WORKDIR/benchmark/rust-bench && cargo build --release --bin rust-bench >/dev/null 2>&1 )"

if [ -n "$NETEM_DELAY" ]; then
  [ -n "$NETEM_IFACE" ] || { echo "BENCH_NETEM_DELAY set but BENCH_NETEM_IFACE missing" >&2; exit 1; }
  echo "applying netem delay=$NETEM_DELAY on ${NETEM_IFACE} (server)..." >&2
  ssh "$SSH" "sudo tc qdisc replace dev $NETEM_IFACE root netem delay $NETEM_DELAY" || {
    echo "netem apply failed (needs passwordless sudo on the server)" >&2
    exit 1
  }
fi

# Start the fixture detached (brace-group idiom so ssh returns immediately).
echo "starting rust-bench on ${SSH}:${PORT} ..." >&2
ssh "$SSH" "{ nohup $BIN --port=$PORT >/tmp/rust-bench-macro-${PORT}.log 2>&1 & disown; }"
for _ in $(seq 1 50); do
  curl -sf -o /dev/null "http://${IP}:${PORT}/hello" 2>/dev/null && break
  sleep 0.2
done
curl -sf -o /dev/null "http://${IP}:${PORT}/hello" || {
  echo "fixture did not become ready at ${IP}:${PORT}" >&2
  exit 1
}

echo "# macro tier: client=local, fixture=${SSH} (${IP}:${PORT}), netem=${NETEM_DELAY:-none}" >&2
BENCH_CLIENT_TARGET="http://${IP}:${PORT}" ./benchmark/bench-client.sh
