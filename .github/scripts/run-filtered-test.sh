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
cd "$(dirname "$0")/../.."
if [ ! -x ./gradlew ]; then
    # `dirname` does not resolve symlinks, so an entry point reached through one
    # lands somewhere without a wrapper. Saying so beats `./gradlew: not found`.
    echo "expected the repository root two levels above $0, found $PWD" >&2
    exit 2
fi

spec="${1:?usage: run-filtered-test.sh <:module:task> <pattern>}"
pattern="${2:?usage: run-filtered-test.sh <:module:task> <pattern>}"

# The results path is derived by string surgery and then handed to `rm -rf`, so
# the shape is checked first. `:jvmTest` alone would derive an empty module and
# delete `/build/test-results/jvmTest` — an absolute path — before `./gradlew`
# ever sees that the spec is wrong. A nested path like `:a:b:task` would derive
# `a:b/…`, which exists nowhere and fails the count for the wrong reason.
case "$spec" in
    :*:*:*) echo "nested project paths are not supported: $spec" >&2; exit 2 ;;
    */*|*..*) echo "a project path has no slashes or dots: $spec" >&2; exit 2 ;;
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
# a line: splitting there and taking the first `tests="…"` of each piece reads
# every element wherever it sits, and cannot pick up a `tests="…"` that appears
# in the CDATA of `<system-out>` — that text follows the tag's own attribute, and
# only the first is taken. Anchoring on the line start instead looked equivalent
# and was not: a document written on one line begins with the `<?xml …?>` prolog,
# so the line never starts at `<testsuite ` and the whole report counts zero.
# Every report Gradle writes today is one element on its own line, so all of this
# is about staying correct if that changes.
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
