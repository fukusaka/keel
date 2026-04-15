#!/usr/bin/env bash
# Pull benchmark results from a remote host.
#
# Usage:
#   ./benchmark/bench-pull.sh <host>
#   BENCH_REMOTE_HOST=<host> ./benchmark/bench-pull.sh
#
# Environment variables:
#   BENCH_REMOTE_HOST     Remote host to pull from (fallback when no arg).
#   BENCH_REMOTE_WORKDIR  Path to the keel checkout on the remote host
#                         (default: ~/prj/keel-work/keel).
#
# Pulls benchmark/results/ from the remote keel working directory into
# the local benchmark/results/ directory.

set -euo pipefail
cd "$(dirname "$0")/.."

HOST="${1:-${BENCH_REMOTE_HOST:-}}"
if [ -z "$HOST" ]; then
  echo "Usage: $(basename "$0") <host>" >&2
  echo "   or: BENCH_REMOTE_HOST=<host> $(basename "$0")" >&2
  exit 1
fi
REMOTE_WORKDIR="${BENCH_REMOTE_WORKDIR:-~/prj/keel-work/keel}"

echo "Pulling results from ${HOST}:${REMOTE_WORKDIR}/benchmark/results/ ..."
rsync -azP "${HOST}:${REMOTE_WORKDIR}/benchmark/results/" benchmark/results/
echo "Done. Results saved to benchmark/results/"
