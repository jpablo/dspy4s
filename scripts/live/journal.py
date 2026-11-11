"""Prints the journal of a session of the harness, one entry on each line.

Usage:
    python3 scripts/live/journal.py ROOT/sessions/ID.db [--width CHARS]

Each line gives the `seq`, the time since the first entry, the source and the kind of the entry, and the body of the
entry as JSON, cut to `--width` characters (1500 by default). An `Activated` shows only the scope, the mode, the model
and the step limit. An `Acted` shows the number of characters of its reasoning and the start of the reasoning. Run it
after the end of the client: the file of an open session can be locked. It needs Python 3.12 and no other package.
"""

import argparse
import json
import sqlite3
import sys


def line(logged: dict, start: int, width: int) -> str:
    """The text of one entry of the journal."""
    at = logged["meta"]["atMillis"]
    source = next(iter(logged["meta"]["source"]))
    entry = logged["entry"]
    kind, body = next(iter(entry.items())) if isinstance(entry, dict) else (entry, {})
    if kind == "Activated":
        config = body["config"]
        body = {k: config[k] for k in ("scope", "mode", "model", "steps")}
    elif kind == "Acted":
        body = dict(body)
        reasoning = body.pop("reasoning", "")
        body["reasoning_chars"] = len(reasoning)
        body["reasoning_head"] = reasoning[:400]
    text = json.dumps(body, ensure_ascii=False)[:width]
    return f"{logged['seq']:>3} +{(at - start) / 1000:7.1f}s {source:<10} {kind}: {text}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("db", help="the file of the session: ROOT/sessions/ID.db")
    parser.add_argument("--width", type=int, default=1500, help="the most characters of each body")
    args = parser.parse_args()
    with sqlite3.connect(f"file:{args.db}?mode=ro", uri=True) as connection:
        rows = connection.execute("select entry from journal order by seq").fetchall()
    if not rows:
        print(f"The journal {args.db} has no entry.", file=sys.stderr)
        return 1
    entries = [json.loads(text) for (text,) in rows]
    start = entries[0]["meta"]["atMillis"]
    for logged in entries:
        print(line(logged, start, args.width))
    return 0


if __name__ == "__main__":
    sys.exit(main())
