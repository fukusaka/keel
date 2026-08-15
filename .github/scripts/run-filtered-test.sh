#!/usr/bin/env bash
#
# Runs one filtered Gradle test task and fails if the filter selected nothing.
#
# A `--tests` pattern that matches no test does not fail the build on a
# Kotlin/Native test task: measured 2026-08-15, `:keel-testing-internal:macosArm64Test
# --tests '*NoSuchTestName*'` exits 0 with BUILD SUCCESSFUL and writes no XML
# report. The same pattern against a JVM task exits 1 with "No tests found for
# given includes", which is what Gradle documents (`failOnNoMatchingTests`
# defaults to true) — so the guard is needed only for the Native half, but it is
# applied to both because the caller should not have to know which is which.
#
# The Native half is not the macOS half: `linuxX64Test` is Kotlin/Native too, so
# four of this workflow's eight invocations are exposed, not two.
#
# Without it, renaming a stress class turns every one of these invocations into a
# silent no-op that reports success.
#
# Usage: run-filtered-test.sh <:module:task> <--tests pattern>
set -euo pipefail

# Both `./gradlew` and the results path are relative to the repository root, and
# the script is two levels below it. Resolving from its own location rather than
# requiring a working directory means a caller outside CI gets the same result.
# Arguments before the directory, so that calling it wrong reports the call
# rather than wherever the caller happened to be. Exit 2 for every rejection —
# 1 is what the guard itself uses, and a caller distinguishing "the filter
# selected nothing" from "you invoked this wrong" needs them apart.
if [ $# -ne 2 ]; then
    echo "usage: run-filtered-test.sh <:module:task> <pattern>" >&2
    exit 2
fi
spec="$1"
pattern="$2"

cd "$(dirname "$0")/../.."
if [ ! -x ./gradlew ]; then
    # Either this is not the repository root — `dirname` does not resolve
    # symlinks, so an entry point reached through one lands elsewhere — or it is
    # and the wrapper is not executable. The message carries both, since where
    # it landed is what tells them apart.
    echo "no executable ./gradlew in $PWD, resolved as the repository root from $0" >&2
    exit 2
fi

# The results path is derived by string surgery and then handed to `rm -rf`, so
# the shape is checked first. `:jvmTest` alone would derive an empty module and
# delete `/build/test-results/jvmTest` — an absolute path — before `./gradlew`
# ever sees that the spec is wrong. A nested path like `:a:b:task` would derive
# `a:b/…`, which exists nowhere and fails the count for the wrong reason. And a
# slash is what path traversal needs, so `:../../etc:jvmTest` is rejected there
# rather than by a rule about dots — single dots are legal in a project name and
# nothing in this tree uses one.
case "$spec" in
    :*:*:*) echo "nested project paths are not supported: $spec" >&2; exit 2 ;;
    */*) echo "a project path has no slashes: $spec" >&2; exit 2 ;;
    :?*:?*) ;;
    *) echo "expected :module:task, got: $spec" >&2; exit 2 ;;
esac

module="${spec%:*}"
module="${module#:}"
task="${spec##*:}"
results="${module}/build/test-results/${task}"

# A previous invocation's XML would satisfy the count below, so the directory
# starts empty. Fresh CI runners have nothing here; local reruns do.
rm -rf "$results"

./gradlew "$spec" --tests "$pattern"

# One awk rather than a grep pipeline: under `set -euo pipefail` a pipeline whose
# first stage finds nothing aborts the script before the check below, which is
# the failure this guard exists to report — it would exit non-zero with no
# message saying why.
#
# The count is anchored on the `<testsuite ` element rather than on the start of
# a line: splitting there and taking the first `tests="…"` of each piece reads an
# element that shares its line with others, which a line anchor does not — a
# document written on one line begins with the `<?xml …?>` prolog, so the line
# never starts at `<testsuite ` and the whole report counts zero.
#
# Two things it still does not do, both measured, neither reachable from a report
# Gradle writes today (823 checked, every one a single element on its own line):
# a start tag split across lines counts zero, because the split piece holds no
# `tests="…"` — that undercounts, so the guard fires; and CDATA that contains a
# literal `<testsuite ` opens a piece of its own, so a `tests="…"` written inside
# it is added — that overcounts, but only on top of an element that already
# declared a non-zero count, so it cannot turn a genuine zero into a pass.
ran=$(awk '
    {
        n = split($0, parts, /<testsuite[ \t]/)
        for (i = 2; i <= n; i++) {
            if (match(parts[i], /tests="[0-9]+"/)) {
                s += substr(parts[i], RSTART + 7, RLENGTH - 8)
            }
        }
    }
    END { print s + 0 }' "$results"/*.xml 2>/dev/null || echo 0)

if [ "$ran" -eq 0 ]; then
    # Both on stdout so the annotation and its explanation stay adjacent in the
    # log. Not because stderr would be ignored — the runner feeds both streams
    # through the same command parser, so the annotation rendered either way.
    echo "::error::${spec} --tests '${pattern}' selected no tests."
    echo "A filter matching nothing exits 0 on a Kotlin/Native test task, so this would have passed silently."
    echo "Check the pattern against the class names that exist, or drop the invocation if the suite is gone."
    exit 1
fi

echo "${spec} --tests '${pattern}' -> ${ran} test(s)"
