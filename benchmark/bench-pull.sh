#!/usr/bin/env bash
# Pull benchmark results from a remote host.
#
# Usage: ./benchmark/bench-pull.sh [host]
#   host: remote hostname (default: luna.local)
#
# Pulls benchmark/results/{host}/ from the remote keel working directory
# into the local benchmark/results/ directory.

set -euo pipefail
cd "$(dirname "$0")/.."

HOST="${1:-luna.local}"
REMOTE_WORKDIR="${BENCH_REMOTE_WORKDIR:-/home/fukusaka/prj/keel-work/keel}"

echo "Pulling results from ${HOST}:${REMOTE_WORKDIR}/benchmark/results/ ..."
rsync -azP "${HOST}:${REMOTE_WORKDIR}/benchmark/results/" benchmark/results/
echo "Done. Results saved to benchmark/results/"
