#!/usr/bin/env bash
# Runs a live work session of the harness in the script mode of the CLI (CODE_HARNESS_DESIGN.md, 15, phase 7).
#
# Usage:
#   scripts/live/chat.sh [--root DIR] [--session ID] [FOLDER] < messages.txt
#
# Each line of the standard input is a message or a command of the CLI, for example `/allow once`. A line waits for
# the end of the turn before it, and the end of the input waits for the open turn. To continue a session in a later
# run, give the id that the first line of the output names, with `--session ID`, or the start of that id.
#
# When the standard input is a terminal, the script runs the CLI for a person, with its line editor and with no
# script mode: scripts/live/chat.sh [FOLDER]. `--list` lists the sessions of the root.
#
# The script needs the variables of the model: OPENAI_BASE_URL, DSPY_MODEL and OPENAI_API_KEY, and
# DSPY_NATIVE_REASONING=true for a model that gives its reasoning apart from its text. It writes each model call to
# the JSON lines file DSPY_TRACE. When DSPY_TRACE is not set, the file is target/harness-live/trace-<time>.jsonl, and
# the script names it on the standard error.
#
# The script builds the CLI with sbt first, and keeps its class path in
# modules/harness-cli/target/live-classpath.txt. HARNESS_CLI_NO_BUILD=1 uses that file with no build.
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
classpath="$repo/modules/harness-cli/target/live-classpath.txt"

if [[ "${HARNESS_CLI_NO_BUILD:-}" != "1" || ! -s "$classpath" ]]; then
  mkdir -p "$(dirname "$classpath")"
  output="$(cd "$repo" && sbt -batch "export harnessCli/Runtime/fullClasspath" 2>&1)" || {
    echo "$output" >&2
    echo "The build of the CLI failed." >&2
    exit 1
  }
  # The last line of the export is the class path. The lines before it are the log of the build.
  printf '%s\n' "$output" | grep '^/' | tail -1 > "$classpath"
  if [[ ! -s "$classpath" ]]; then
    echo "$output" >&2
    echo "The build gave no class path." >&2
    exit 1
  fi
fi

if [[ -z "${DSPY_TRACE:-}" ]]; then
  mkdir -p "$repo/target/harness-live"
  export DSPY_TRACE="$repo/target/harness-live/trace-$(date +%Y%m%d-%H%M%S).jsonl"
fi
echo "The trace of the model calls: $DSPY_TRACE" >&2
if [[ -z "${DSPY_MODEL:-}" ]]; then
  echo "DSPY_MODEL is not set, so each policy call fails." >&2
fi

if [[ -t 0 ]]; then
  # A person at a terminal: the line editor of the CLI.
  exec java -cp "$(cat "$classpath")" dspy4s.harness.cli.Cli "$@"
fi
exec java -cp "$(cat "$classpath")" dspy4s.harness.cli.Cli --script "$@"
