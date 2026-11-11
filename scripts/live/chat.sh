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
# DSPY_NATIVE_REASONING=true for a model that gives its reasoning apart from its text. They can come from the
# environment or from the file ~/.dspy4s/harness/.env, which the CLI reads at its start. It writes each model call to
# the JSON lines file DSPY_TRACE. When DSPY_TRACE is not set, the file is target/harness-live/trace-<time>.jsonl, and
# the script names it on the standard error.
#
# The script runs the CLI with scripts/dspy4s-harness, which builds the CLI when its class path is missing or older
# than a source file. HARNESS_CLI_NO_BUILD=1 uses the class path with no build.
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"

if [[ -z "${DSPY_TRACE:-}" ]]; then
  mkdir -p "$repo/target/harness-live"
  export DSPY_TRACE="$repo/target/harness-live/trace-$(date +%Y%m%d-%H%M%S).jsonl"
fi
echo "The trace of the model calls: $DSPY_TRACE" >&2
if [[ -z "${DSPY_MODEL:-}" ]] && ! grep -qE '^[[:space:]]*(export[[:space:]]+)?DSPY_MODEL=' "$HOME/.dspy4s/harness/.env" 2>/dev/null; then
  echo "DSPY_MODEL is not set here or in ~/.dspy4s/harness/.env, so each policy call fails." >&2
fi

if [[ -t 0 ]]; then
  # A person at a terminal: the line editor of the CLI.
  exec "$repo/scripts/dspy4s-harness" "$@"
fi
exec "$repo/scripts/dspy4s-harness" --script "$@"
