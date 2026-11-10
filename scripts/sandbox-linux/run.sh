#!/bin/bash
# Runs the laws of harness-sandbox on Linux, in a podman container with bwrap (CODE_HARNESS_DESIGN.md, 5.4). With
# MODULE=harness-host, it runs the laws of the host: the jobs, the stops and the crash of 10.3 under bwrap.
#
# The escape tests of bubblewrap skip on macOS. This script compiles the tests with sbt on the host, then runs them in
# the container as the normal user `ubuntu`, so bwrap uses user namespaces as on a Linux desktop. The container is
# privileged, because a container with the usual options does not let bwrap mount /dev/pts and /proc. The JVM is PID 1
# of the container and has no init, so the orphans of the tests stay zombies.
#
# The report goes to modules/$MODULE/target/test-reports/TEST-linux.xml, where the blueprint reads it with the reports
# of sbt. Run the script again after a change to the sandbox: the blueprint does not know that a report is old.
#
# Usage:
#   scripts/sandbox-linux/run.sh            runs each test class of harness-sandbox
#   scripts/sandbox-linux/run.sh CLASS...   runs these classes, for example dspy4s.harness.sandbox.BubblewrapLaws
# Environment:
#   MODULE=harness-host   runs the laws of harness-host. The default is harness-sandbox
#   INIT=1          puts an init process as PID 1, which reaps the orphans
#   MODE=labeloff   runs where bwrap can mount /dev but not /proc: bwrap must not work, and its tests skip
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
IMAGE=localhost/dspy4s-sandbox-linux:1
MODULE=${MODULE:-harness-sandbox}
case "$MODULE" in
  harness-sandbox) PROJECT=harnessSandbox PACKAGE=sandbox ;;
  harness-host) PROJECT=harnessHost PACKAGE=host ;;
  *) echo "no Linux laws for the module $MODULE" >&2; exit 1 ;;
esac
TESTS=$ROOT/modules/$MODULE/target
REPORTS=$TESTS/test-reports

if ! podman image exists "$IMAGE"; then
  podman build -t "$IMAGE" "$ROOT/scripts/sandbox-linux"
fi

CP=$(cd "$ROOT" && sbt -batch -no-colors "harnessSandbox/Test/compile" "export $PROJECT/Test/fullClasspath" | tail -1)
case "$CP" in /*) ;; *) echo "sbt gave no classpath: $CP" >&2; exit 1 ;; esac
# LinuxReport is a test class of harness-sandbox.
CP="$CP:$(ls -d "$ROOT"/modules/harness-sandbox/target/scala-*/test-classes | head -1)"

if [ $# -eq 0 ]; then
  CLASSES=()
  while IFS= read -r file; do
    CLASSES+=("dspy4s.harness.$PACKAGE.$(basename "$file" .class)")
  done < <(find "$TESTS"/scala-*/test-classes/dspy4s/harness/"$PACKAGE" -name '*Laws.class' | sort)
else
  CLASSES=("$@")
fi

# Each part of the classpath at its path on the host, read-only. Only the report folder is writable.
MOUNTS=(-v "$ROOT:$ROOT:ro")
IFS=: read -r -a PARTS <<< "$CP"
for part in "${PARTS[@]}"; do
  case "$part" in "$ROOT"/*) ;; *) MOUNTS+=(-v "$part:$part:ro") ;; esac
done
mkdir -p "$REPORTS"
MOUNTS+=(-v "$REPORTS:$REPORTS")

FLAGS=(--privileged)
if [ "${MODE:-}" = labeloff ]; then FLAGS=(--security-opt label=disable); fi
if [ "${INIT:-0}" = 1 ]; then FLAGS+=(--init); fi

# The same fake value as Test / envVars in build.sbt. A child must not see it.
podman run --rm "${FLAGS[@]}" "${MOUNTS[@]}" \
  -e DSPY4S_FAKE_SECRET_TOKEN=fake-value-for-the-escape-tests \
  -w /tmp \
  "$IMAGE" \
  java -cp "$CP" dspy4s.harness.sandbox.LinuxReport "$REPORTS/TEST-linux.xml" "${CLASSES[@]}"
