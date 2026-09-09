#!/usr/bin/env bash
#
# gate.sh — run the PR pre-merge gate on one remote host (or both).
#
# Two hosts are needed for full coverage. A macOS host builds macosArm64, which
# CI does not build at all; a Linux host builds linuxX64, which a macOS host
# cannot cross-compile. Neither host covers the other's target.
#
# Every element below is load-bearing and was added after a failure, which is
# why this is a script and not a command line in a document:
#
#   --stop before the run   A daemon's heap accumulates across a long session
#                           until the compiler OOMs; the ssh call then hangs
#                           with no output.
#   timeout                 Turns that hang into a non-zero exit instead of an
#                           unbounded wait.
#   remote log + </dev/null Killing gradle does not free the ssh pipe if the
#                           daemon inherited it, so ssh keeps running after the
#                           timeout fires. Writing to a file on the remote and
#                           closing stdin releases it.
#   -Xmx6g                  The 4g default OOMs the compiler on keel-core, and
#                           an interrupted incremental JS compile then fails
#                           with "cannot find external signature" until the
#                           module's build dir is removed.
#   archive test-results    A rerun overwrites the failing run's XML, and
#   unconditionally         rerunning is the natural next move. Archiving only
#                           on failure loses the evidence to that same reflex.
#                           Fed to tar through -print0/--null rather than as
#                           arguments: the tree holds ~15k such files and the
#                           argument form fails with E2BIG. Its stderr goes to
#                           the log, not to /dev/null, because that failure is
#                           exactly what hid this one.
#   aggregate tasks         Enumerating modules misses satellite modules
#   and no --tests filter   (keel-tls-*, keel-testing-*); a filter hides
#                           pre-existing failures the gate exists to catch.
#
# usage:
#   KEEL_GATE_MAC_HOST=<host>   scripts/gate.sh mac
#   KEEL_GATE_LINUX_HOST=<host> scripts/gate.sh linux
#   KEEL_GATE_MAC_HOST=<host> KEEL_GATE_LINUX_HOST=<host> scripts/gate.sh both
#
# env:
#   KEEL_GATE_MAC_HOST     ssh target that builds macosArm64   (required for mac)
#   KEEL_GATE_LINUX_HOST   ssh target that builds linuxX64     (required for linux)
#   KEEL_GATE_MAC_DIR      remote workdir  (default: ~/prj/keel-work/keel)
#   KEEL_GATE_LINUX_DIR    remote workdir  (default: ~/prj/keel-work/keel)
#   KEEL_GATE_TIMEOUT      seconds         (default: 900)
#   KEEL_GATE_JVMARGS      daemon args     (default: -Xmx6g -XX:MaxMetaspaceSize=1g)
#   KEEL_GATE_NO_SYNC      set to 1 to skip rsync (re-run on an already-synced tree)
#   KEEL_GATE_RSYNC_DELETE set to 1 to add --delete (never --delete-excluded,
#                          which would delete .git, the build output and
#                          benchmark/results from the remote). Plain -az
#                          leaves a file the local tree deleted in place on the
#                          remote, where it can still be compiled. Use it when a
#                          remote-only compile error names a file you removed.
#
# Exits non-zero if any host's gate fails, after printing the tail of that
# host's log and the path of its archived test-results.

# Invariants every path in this file has to keep. Three review rounds found
# defects, and each round's fix introduced one; these are what those defects
# had in common, written down so an edit can be checked against them:
#
#   1. Reject a bad invocation before doing any work, and never treat an
#      unrecognised argument as a narrower run that then exits 0.
#   2. Bound every call that reaches a remote host, and never make the bound a
#      hard dependency on a tool this machine may not have.
#   3. Never discard a verdict the remote already produced, and never report a
#      failure without the paths to the log and the archive: their names carry
#      a timestamp nobody can reconstruct.
#   4. State only what has been checked. An archive that exists is not an
#      archive with test results in it.
#   5. Clean up what a run leaves on the remote.

set -uo pipefail

MODE="${1:?usage: gate.sh <mac|linux|both>}"
TIMEOUT="${KEEL_GATE_TIMEOUT:-900}"
JVMARGS="${KEEL_GATE_JVMARGS:--Xmx6g -XX:MaxMetaspaceSize=1g}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# The exclude list is shared with scripts/bench-sync.sh; one copy, one place to
# change when a new generated directory appears.
EXCLUDES="${REPO_ROOT}/scripts/rsync-excludes.txt"

# Source sets differ by host because the intermediate ones a host cannot build
# are not merely empty, they fail. detektTestSources is separate from the rest
# because detekt's KMP support builds no detektMetadata<X>Test task, so no other
# task lints commonTest / nativeTest / macosTest / linuxTest / appleTest.
MAC_TASKS="detektMetadataCommonMain detektMetadataNativeMain detektMetadataAppleMain \
detektMetadataMacosMain detektJvmMain detektMacosArm64Main detektJsMain detektTestSources \
jvmTest macosArm64Test jsNodeTest dokkaGeneratePublicationHtml"

LINUX_TASKS="detektMetadataCommonMain detektMetadataNativeMain detektMetadataLinuxMain \
detektJvmMain detektLinuxX64Main detektJsMain detektTestSources \
jvmTest linuxX64Test jsNodeTest dokkaGeneratePublicationHtml"

# A non-interactive zsh reads ~/.zshenv but not ~/.zshrc, so the Homebrew and
# sdkman entries a macOS host puts in ~/.zshrc are absent here and have to be
# named. A Linux host needs nothing.
MAC_PRELUDE='source ~/.sdkman/bin/sdkman-init.sh; export PATH=/opt/homebrew/bin:/opt/homebrew/sbin:$PATH;'

# The prelude above is sent to the remote; it does not put timeout on this
# machine's PATH. timeout is not in a base macOS install (it comes with
# coreutils), and without this shim a passing gate would report FAIL because
# the small ssh calls that read the result would fail with command not found.
# The bound is a convenience on those calls, not a correctness requirement, so
# running without it is better than refusing to run.
if command -v timeout >/dev/null 2>&1; then
    bounded() { timeout "$@"; }
else
    bounded() { shift; "$@"; }
fi

run_one() {
    local label="$1" host="$2" dir="$3" tasks="$4" prelude="$5"
    local stamp; stamp="$(date +%Y%m%d-%H%M%S)"
    # All three carry the stamp. A fixed-name log is truncated by a second run
    # on the same host, and a fixed-name exit file is read by whichever run asks
    # first; a detekt or compile failure writes no test-results XML at all, so
    # the log is the only evidence that kind of failure leaves.
    local log="/tmp/keel-gate-${label}-${stamp}.log"
    local exitf="/tmp/keel-gate-${label}-${stamp}.exit"
    local arch="/tmp/keel-gate-${label}-${stamp}.tgz"

    echo "=== gate: ${label} (${host}) ==="

    if [ "${KEEL_GATE_NO_SYNC:-0}" != "1" ]; then
        # --delete only. NOT --delete-excluded: the exclude list names .git,
        # build output and benchmark/results, and --delete-excluded deletes
        # exactly those from the remote — measured, it left only the files this
        # sync had just written. Plain --delete removes what this fix is for: a
        # source file deleted locally that the remote would still compile.
        local del=()
        [ "${KEEL_GATE_RSYNC_DELETE:-0}" = "1" ] && del=(--delete)
        echo "--- rsync${del:+ (--delete)}"
        # ${del[@]+...} rather than "${del[@]}": bash 3.2, which is what /bin/bash
        # is on macOS, treats an empty array as unset under set -u and aborts.
        rsync -az ${del[@]+"${del[@]}"} --exclude-from="$EXCLUDES" "${REPO_ROOT}/" "${host}:${dir}/" || {
            echo "ERROR: rsync to ${host} failed" >&2; return 1; }
    fi

    echo "--- gradle (timeout ${TIMEOUT}s)"
    # The whole remote command is one quoted string so the local shell does not
    # expand $PATH, $?, or the log paths. --stop is followed by ';' rather than
    # '&&' so that having no daemon to stop does not skip the gate; the cd uses
    # '|| exit' rather than '&&' for the opposite reason, because '&&' would
    # bind only as far as the first ';' and let the gate run in the login
    # directory. ${dir} is deliberately unquoted so the remote shell expands a
    # leading ~. Quoting it (either way) makes cd fail on the default path.
    # The archive is taken whether or not the gate passed, before a rerun can
    # overwrite the XML.
    ssh "$host" "cd ${dir} || exit 111; ${prelude} \
        timeout ${TIMEOUT} ./gradlew --stop >/dev/null 2>&1; \
        timeout ${TIMEOUT} ./gradlew --continue -Ptls -Pbenchmark \
            -Dorg.gradle.jvmargs='${JVMARGS}' \
            ${tasks} > '${log}' 2>&1 </dev/null; \
        echo \$? > '${exitf}'; \
        find /tmp -maxdepth 1 -name 'keel-gate-${label}-*' -mtime +7 -delete 2>/dev/null; \
        find . -path '*/build/test-results/*' -name '*.xml' -print0 2>/dev/null \
            | tar czf '${arch}' --null -T - >> '${log}' 2>&1 \
            || echo 'gate.sh: archiving test-results failed (see above)' >> '${log}'; \
        true" </dev/null
    local sshrc=$?

    # The remote command ends in `true`, so a non-zero status here is the ssh
    # itself failing (a dropped connection, a refused auth). Ask for this run's
    # exit file anyway: a connection that drops during the teardown of a long
    # run has still produced a verdict, and throwing it away costs another full
    # run on both hosts. Only an empty answer means the gate did not run. This
    # cannot resurrect an older run's verdict, because the file's name carries
    # this run's timestamp — that is what makes reading it here safe.
    local code
    code="$(bounded 60 ssh "$host" "cat '${exitf}' 2>/dev/null" </dev/null)"

    if [ -z "$code" ]; then
        # Empty means one of two things and the difference matters: the gate
        # never ran, or it ran and this second ssh could not bring the answer
        # back. A connection that dropped during teardown does not heal in
        # between, so the second reading is the likely one, and the evidence is
        # sitting on the remote under a name whose timestamp nobody can guess.
        # Print the paths either way.
        echo "--- ${label}: FAIL (ssh exited ${sshrc}; no verdict could be read)" >&2
        echo "--- if the run got that far, its log is ${host}:${log} and its test-results ${host}:${arch}" >&2
        return 1
    fi
    [ $sshrc -ne 0 ] && echo "--- ${label}: ssh exited ${sshrc} after the run; reading its verdict" >&2

    # Ask whether the archive exists rather than asserting it does. tar's own
    # failure goes to the log, and the log is only shown on FAIL, so on a pass
    # an unwritable or full /tmp would otherwise be announced as an archive.
    local archnote
    archnote="$(bounded 60 ssh "$host" "test -s '${arch}' && echo '${arch}'" </dev/null)"
    archnote="${archnote:-<archiving failed, see the log>}"

    if [ "$code" -eq 0 ]; then
        echo "--- ${label}: PASS (log ${host}:${log}, test-results ${host}:${archnote})"
        return 0
    fi

    # 124 is the remote timeout firing. Say that, and no more: with --continue a
    # failing run keeps going through the remaining task groups, so an overrun
    # is as likely to be a long failing run as the daemon hang it used to mean.
    echo "--- ${label}: FAIL (exit ${code}$([ "$code" -eq 124 ] && echo " = hit the ${TIMEOUT}s timeout"))" >&2
    # Bounded: a host wedged badly enough to fire the timeout above can leave
    # this one hanging too, and then the failure prints nothing at all. 120
    # lines rather than 40 because --continue reports every failed task group,
    # and the first of them is the one 40 lines would cut off.
    bounded 60 ssh "$host" "tail -120 '${log}'" </dev/null >&2
    # Named, not characterised: a detekt or compile failure produces no
    # test-results XML at all, so this archive can legitimately be empty.
    echo "--- test-results archive on ${host}: ${archnote}" >&2
    echo "--- full log on ${host}: ${log}" >&2
    return 1
}

rc=0
case "$MODE" in
    mac|linux|both) ;;
    *) echo "usage: gate.sh <mac|linux|both>" >&2; exit 2 ;;
esac

# Invariant 1. A second argument used to be ignored, so `gate.sh mac linux` ran
# one host and exited 0 — a one-host green, which is a partial gate reported as
# a full one.
if [ $# -gt 1 ]; then
    echo "usage: gate.sh <mac|linux|both> (unexpected argument: ${2})" >&2; exit 2
fi

# Both hosts are checked here, not inside the branches below. Checking inline
# meant `both` ran the entire macOS gate — a quarter of an hour on a shared
# host — before finding out the Linux host was never set.
if [ "$MODE" = "mac" ] || [ "$MODE" = "both" ]; then
    : "${KEEL_GATE_MAC_HOST:?KEEL_GATE_MAC_HOST is required (ssh target that builds macosArm64)}"
fi
if [ "$MODE" = "linux" ] || [ "$MODE" = "both" ]; then
    : "${KEEL_GATE_LINUX_HOST:?KEEL_GATE_LINUX_HOST is required (ssh target that builds linuxX64)}"
fi

if [ "$MODE" = "mac" ] || [ "$MODE" = "both" ]; then
    run_one mac "$KEEL_GATE_MAC_HOST" "${KEEL_GATE_MAC_DIR:-~/prj/keel-work/keel}" \
        "$MAC_TASKS" "$MAC_PRELUDE" || rc=1
fi

if [ "$MODE" = "linux" ] || [ "$MODE" = "both" ]; then
    # Both hosts run even if the first failed: a gate that stops at the first
    # host hides the other host's failures for a round, which is the cost this
    # gate exists to avoid.
    run_one linux "$KEEL_GATE_LINUX_HOST" "${KEEL_GATE_LINUX_DIR:-~/prj/keel-work/keel}" \
        "$LINUX_TASKS" "" || rc=1
fi

exit $rc
