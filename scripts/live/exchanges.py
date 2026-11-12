"""Prints the exchanges of a session of the harness with the model endpoint, one exchange on each line.

Usage:
    python3 scripts/live/exchanges.py ROOT/sessions/ID.http.jsonl [--width CHARS]

The file is the exchange file of the session (docs/code-harness/OBSERVABILITY.md, 4). Each line gives the number of
the exchange from 1, the last `seq` of the journal before it, the time since the first exchange, the predictor (or the
probe), the status and the end of the reply, the time of the reply, the size of the request body, the tokens of the
usage, and the start of the text of the reply, cut to `--width` characters (200 by default). An exchange with no
reply line shows `open`. The command `scripts/dspy4s-harness --inspect --root ROOT` shows each exchange in full. It
needs Python 3.12 and no other package.
"""

import argparse
import json
import sys
from collections.abc import Iterable


def chunks(lines: Iterable[str]) -> Iterable[dict]:
    """The JSON objects of the `data:` lines of a stream, without the line `data: [DONE]`."""
    for line in lines:
        if not line.startswith("data:"):
            continue
        data = line[len("data:") :].strip()
        if data == "[DONE]":
            continue
        try:
            value = json.loads(data)
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict):
            yield value


def first_choice(value: dict) -> dict:
    """The first choice of a reply or of a chunk, or an empty object."""
    choices = value.get("choices") or []
    return choices[0] if choices and isinstance(choices[0], dict) else {}


def reading(reply: dict) -> tuple[str, dict]:
    """The text of a reply (the reasoning when the reply has no text) and its usage."""
    if "lines" in reply:
        text: list[str] = []
        reasoning: list[str] = []
        usage: dict = {}
        for chunk in chunks(reply["lines"]):
            delta = first_choice(chunk).get("delta") or {}
            text.append(delta.get("content") or "")
            reasoning.append(delta.get("reasoning_content") or delta.get("reasoning") or "")
            usage = chunk.get("usage") or usage
        return ("".join(text) or "".join(reasoning)), usage
    try:
        body = json.loads(reply.get("body", ""))
    except json.JSONDecodeError:
        return reply.get("body", ""), {}
    if not isinstance(body, dict):
        return "", {}
    message = first_choice(body).get("message") or {}
    text = message.get("content") or message.get("reasoning_content") or message.get("reasoning") or ""
    if not text and message.get("tool_calls"):
        text = json.dumps(message["tool_calls"], ensure_ascii=False)
    return text, body.get("usage") or {}


def line(n: int, request: dict, reply: dict | None, start: int, width: int) -> str:
    """The text of one exchange."""
    who = request.get("predictor") or "none"
    if "probe" in request:
        who = f"{who} (probe {request['probe']})"
    size = len(request.get("body", "").encode("utf-8"))
    head = f"{n:>3} after #{request.get('after', '?'):<4} +{(request['at'] - start) / 1000:7.1f}s {who:<12}"
    if reply is None:
        return f"{head} open {size} B sent"
    status = reply.get("status", "-")
    took = (reply["at"] - request["at"]) / 1000
    text, usage = reading(reply)
    tokens = f"in {usage.get('prompt_tokens', '?')} out {usage.get('completion_tokens', '?')}"
    end = f"{reply.get('end', '?'):<6}"
    if "error" in reply:
        end = f"{end.strip()}: {reply['error']}"
    flat = " ".join(text.split())[:width]
    return f"{head} {status} {end} {took:6.2f}s {size} B sent, {tokens} | {flat}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("file", help="the exchange file of the session: ROOT/sessions/ID.http.jsonl")
    parser.add_argument("--width", type=int, default=200, help="the most characters of the text of each reply")
    args = parser.parse_args()
    requests: list[dict] = []
    replies: dict[str, dict] = {}
    with open(args.file, encoding="utf-8") as file:
        for raw in file:
            try:
                value = json.loads(raw)
            except json.JSONDecodeError:
                print(f"A line that is not JSON: {raw[:80]!r}", file=sys.stderr)
                continue
            if value.get("kind") == "request":
                requests.append(value)
            elif value.get("kind") == "reply":
                replies[value["id"]] = value
    if not requests:
        print("The file has no exchange.")
        return 0
    start = requests[0]["at"]
    for n, request in enumerate(requests, start=1):
        print(line(n, request, replies.get(request["id"]), start, args.width))
    return 0


if __name__ == "__main__":
    sys.exit(main())
