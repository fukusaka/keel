#!/usr/bin/env bash
#
# bench-sync.sh — sync the tree to a bench host and build what a sweep needs.
#
# The build set is always three artefacts, not one: bench-all.sh and
# bench-stream-all.sh call preflight_check_primary_binaries and refuse to start
# if the host-appropriate native kexe, the JVM classpath file, or the JS
# production bin is missing. A sweep that silently skipped the JS engines once
# left every Node.js row in the result tables marked as needing re-measurement.
#
# --no-configuration-cache is required, on every host: :benchmark:writeClasspath
# cannot be stored in the configuration cache (measured 2026-09-08 on Linux and
# previously on macOS — "error writing value of type 'kotlin.SynchronizedLazyImpl'"
# plus DefaultSourceSet / JavaCompile, 4 problems). The rule file used to document
# this for the macOS host only; the Linux command as written there failed.
#
# The classpath file is written with ${REPO_ROOT} / ${GRADLE_USER_HOME}
# placeholders that are expanded on the host that runs the bench, so it must be
# generated on that host — copying one between hosts leaves the placeholders in
# place and every JVM engine fails its readiness timeout.
#
# usage:
#   KEEL_BENCH_MAC_HOST=<host>   scripts/bench-sync.sh mac   [--native-refs]
#   KEEL_BENCH_LINUX_HOST=<host> scripts/bench-sync.sh linux [--native-refs]
#
# env:
#   KEEL_BENCH_MAC_HOST     ssh target that builds macosArm64   (required for mac)
#   KEEL_BENCH_LINUX_HOST   ssh target that builds linuxX64     (required for linux)
#   KEEL_BENCH_MAC_DIR      remote workdir  (default: ~/prj/keel-work/keel)
#   KEEL_BENCH_LINUX_DIR    remote workdir  (default: ~/prj/keel-work/keel)
#   KEEL_BENCH_NO_SYNC      set to 1 to build without syncing first
#   KEEL_BENCH_TIMEOUT      seconds         (default: 1800)
#   KEEL_BENCH_JVMARGS      daemon args     (default: -Xmx6g -XX:MaxMetaspaceSize=1g)
#
# --native-refs additionally rebuilds the non-Kotlin reference servers, whose
# artefacts the sync excludes because they are platform-specific. Only rust-bench
# and go-bench are covered here; the zig-bench and swift-bench server builds are
# not scripted (their client variants are built on demand by bench-client.sh).
#
# This does not run a sweep. Run bench-keel.sh / bench-all.sh on the host after.

set -uo pipefail

MODE="${1:?usage: bench-sync.sh <mac|linux> [--native-refs]}"
NATIVE_REFS=0
[ "${2:-}" = "--native-refs" ] && NATIVE_REFS=1
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EXCLUDES="${REPO_ROOT}/scripts/rsync-excludes.txt"
# Longer than the gate's 900s: this links a release native executable.
TIMEOUT="${KEEL_BENCH_TIMEOUT:-1800}"
JVMARGS="${KEEL_BENCH_JVMARGS:--Xmx6g -XX:MaxMetaspaceSize=1g}"

# A non-interactive zsh reads ~/.zshenv but not ~/.zshrc, so a macOS host's
# Homebrew and sdkman entries have to be named here. A Linux host needs nothing.
MAC_PRELUDE='source ~/.sdkman/bin/sdkman-init.sh; export PATH=/opt/homebrew/bin:/opt/homebrew/sbin:$PATH;'

case "$MODE" in
    mac)   : "${KEEL_BENCH_MAC_HOST:?KEEL_BENCH_MAC_HOST is required (ssh target that builds macosArm64)}"
           HOST="$KEEL_BENCH_MAC_HOST"; DIR="${KEEL_BENCH_MAC_DIR:-~/prj/keel-work/keel}"
           NATIVE_TASK=":benchmark:linkReleaseExecutableMacosArm64"; PRELUDE="$MAC_PRELUDE" ;;
    linux) : "${KEEL_BENCH_LINUX_HOST:?KEEL_BENCH_LINUX_HOST is required (ssh target that builds linuxX64)}"
           HOST="$KEEL_BENCH_LINUX_HOST"; DIR="${KEEL_BENCH_LINUX_DIR:-~/prj/keel-work/keel}"
           NATIVE_TASK=":benchmark:linkReleaseExecutableLinuxX64"; PRELUDE="" ;;
    *)     echo "usage: bench-sync.sh <mac|linux> [--native-refs]" >&2; exit 2 ;;
esac

if [ "${KEEL_BENCH_NO_SYNC:-0}" != "1" ]; then
    echo "--- rsync → ${HOST}"
    rsync -az --exclude-from="$EXCLUDES" "${REPO_ROOT}/" "${HOST}:${DIR}/" || {
        echo "ERROR: rsync to ${HOST} failed" >&2; exit 1; }
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
LOG="/tmp/keel-bench-sync-${MODE}-${STAMP}.log"

echo "--- build (native + classpath + js), timeout ${TIMEOUT}s, log ${HOST}:${LOG}"
# Same three guards gate.sh needs, for the same reasons: a timeout so a stalled
# daemon becomes an exit code instead of an unbounded wait, output to a file on
# the remote with stdin closed so a daemon holding the inherited pipe cannot
# keep ssh alive past that timeout, and a heap above the 4g in gradle.properties,
# which OOMs the compiler on this build. ${DIR} is unquoted so the remote shell
# expands a leading ~.
ssh "$HOST" "cd ${DIR} && ${PRELUDE} \
    timeout ${TIMEOUT} ./gradlew --no-configuration-cache -Pbenchmark \
        -Dorg.gradle.jvmargs='${JVMARGS}' \
        ${NATIVE_TASK} :benchmark:writeClasspath :benchmark:compileProductionExecutableKotlinJs \
        > '${LOG}' 2>&1 </dev/null" </dev/null || {
    echo "ERROR: gradle build failed on ${HOST} (log ${HOST}:${LOG})" >&2
    timeout 60 ssh "$HOST" "tail -40 '${LOG}'" </dev/null >&2
    exit 1; }

if [ "$NATIVE_REFS" = "1" ]; then
    echo "--- non-Kotlin reference servers (rust-bench, go-bench)"
    # ${PRELUDE} here too. cargo and go come from Homebrew on a macOS host, and
    # a non-interactive zsh does not put it on PATH, so without this the two
    # builds fail with command not found on mac and only on mac.
    ssh "$HOST" "cd ${DIR}/benchmark/rust-bench && ${PRELUDE} cargo build --release" </dev/null || {
        echo "ERROR: cargo build failed on ${HOST}" >&2; exit 1; }
    ssh "$HOST" "cd ${DIR}/benchmark/go-bench && ${PRELUDE} go build -o go-bench ." </dev/null || {
        echo "ERROR: go build failed on ${HOST}" >&2; exit 1; }
fi

echo "--- ${MODE}: ready. Run the sweep on ${HOST}, then ./benchmark/bench-pull.sh <host>"
