#!/usr/bin/env bash
# Runs one live scenario of scripts/live/scenarios with chat.sh (CODE_HARNESS_DESIGN.md, 15, phase 7).
#
# Usage:
#   scripts/live/scenario.sh NAME
#
# A scenario is a folder scripts/live/scenarios/NAME with:
#   - ws/             the files of the folder of the session (optional)
#   - setup.sh        a script that runs in the copy of ws before the session, for example to make a large file
#                     (optional)
#   - messages-N.txt  the lines of run N, in the order of N. Run 1 opens a new session, and each later run opens
#                     the same session again with --session, so a scenario can test a reopen.
#
# The script copies the scenario to target/harness-live/NAME-<time>, runs each file of messages, and prints the
# output of each run, the journal of the session, its exchanges with the model and the folder. The folder keeps the
# root of the host, with the journal and the exchange file of the session, and the output of each run (run-N.txt).
# The variables of the model are as for chat.sh.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
name="${1:?Give the name of a scenario: $(ls "$here/scenarios" | tr '\n' ' ')}"
scenario="$here/scenarios/$name"
if [[ ! -d "$scenario" ]]; then
  echo "There is no scenario $name. The scenarios: $(ls "$here/scenarios" | tr '\n' ' ')" >&2
  exit 2
fi

out="$repo/target/harness-live/$name-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out/root" "$out/ws"
if [[ -d "$scenario/ws" ]]; then cp -R "$scenario/ws/." "$out/ws/"; fi
if [[ -x "$scenario/setup.sh" ]]; then (cd "$out/ws" && "$scenario/setup.sh"); fi

session=""
run=0
for messages in "$scenario"/messages-*.txt; do
  run=$((run + 1))
  log="$out/run-$run.txt"
  start=$(date +%s)
  code=0
  if [[ -z "$session" ]]; then
    (cd "$out/ws" && "$here/chat.sh" --root "$out/root" . < "$messages" > "$log" 2>&1) || code=$?
    session="$(sed -n 's/^The session \([^ ]*\) is open.*/\1/p' "$log" | head -1)"
  else
    (cd "$out/ws" && HARNESS_CLI_NO_BUILD=1 "$here/chat.sh" --root "$out/root" --session "$session" \
      < "$messages" > "$log" 2>&1) || code=$?
  fi
  echo "== run $run ($(basename "$messages")): exit $code after $(( $(date +%s) - start )) s"
  grep -v '^SLF4J' "$log" || true
  if [[ -z "$session" ]]; then
    echo "== the session did not open" >&2
    exit 1
  fi
done

echo "== the journal of the session $session"
python3 "$here/journal.py" "$out/root/sessions/$session.db" --width 600
exchanges="$out/root/sessions/$session.http.jsonl"
if [[ -f "$exchanges" ]]; then
  echo "== the exchanges of the session $session with the model"
  python3 "$here/exchanges.py" "$exchanges"
fi
echo "== the folder: $out"
echo "== the inspector: $repo/scripts/dspy4s-harness --inspect --root $out/root"
