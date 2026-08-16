#!/usr/bin/env python3
"""Small, dependency-free helpers for the dspy4s JMH comparison workflow."""

from __future__ import annotations

import argparse
import json
import math
import pathlib
import re
import sys


MODULE_BENCHES = {
    "modules/algebra/": ["OpticBench"],
    "modules/programs/": ["DeepProgramBench"],
    "modules/core/": ["DeepProgramBench"],
    "modules/lm/": ["DeepProgramBench"],
    "modules/adapters/": ["DeepProgramBench"],
    "modules/signatures/": ["DeepProgramBench"],
}
FULL_TRIGGERS = ("benchmarks/", "build.sbt", "project/")
IGNORED_PREFIXES = (".github/", "docs/", "site/", "README.md", "LICENSE", ".scalafmt.conf")
BENCH_SOURCE = pathlib.Path("benchmarks/src/main/scala/dspy4s/bench")
GC_KEYS = ("gc.alloc.rate.norm", "·gc.alloc.rate.norm")


def affected(paths: list[str]) -> str:
    classes: set[str] = set()
    for raw in paths:
        path = raw.strip()
        if not path:
            continue
        if path.startswith(FULL_TRIGGERS):
            return "FULL"
        for prefix, benchmarks in MODULE_BENCHES.items():
            if path.startswith(prefix):
                classes.update(benchmarks)
                break
        else:
            if not path.startswith(IGNORED_PREFIXES):
                return "FULL"
    if not classes:
        return ""
    return "(" + "|".join(sorted(re.escape(name) for name in classes)) + ")\\..*"


def validate_mapping(root: pathlib.Path) -> list[str]:
    source = root / BENCH_SOURCE
    if not source.is_dir():
        return [f"benchmark source directory is missing: {BENCH_SOURCE}"]
    on_disk = {path.stem for path in source.glob("*Bench.scala")}
    errors: list[str] = []
    for prefix, classes in MODULE_BENCHES.items():
        if not (root / prefix).exists():
            errors.append(f"mapped source directory is missing: {prefix}")
        for name in classes:
            if name not in on_disk:
                errors.append(f"mapped benchmark is missing: {name}")
    return errors


def load_results(path: pathlib.Path) -> dict[tuple[str, tuple], dict]:
    loaded: dict[tuple[str, tuple], dict] = {}
    for entry in json.loads(path.read_text()):
        parameters = entry.get("params") or {}
        metric = entry["primaryMetric"]
        secondary = entry.get("secondaryMetrics") or {}
        allocation = next((secondary[key]["score"] for key in GC_KEYS if key in secondary), None)
        key = (entry["benchmark"], tuple(sorted(parameters.items())))
        loaded[key] = {
            "benchmark": entry["benchmark"],
            "params": parameters,
            "time": metric["score"],
            "time_unit": metric.get("scoreUnit", "ns/op"),
            "bytes_per_operation": allocation,
        }
    return loaded


def percentage(base: float | None, head: float | None) -> float | None:
    if base is None or head is None:
        return None
    if base == 0:
        return None if head == 0 else math.inf
    return (head - base) / base * 100.0


def compare(base: dict, head: dict) -> dict:
    rows = []
    for key in sorted(set(base) & set(head), key=str):
        before = base[key]
        after = head[key]
        rows.append(
            {
                "benchmark": after["benchmark"],
                "params": after["params"],
                "time_base": before["time"],
                "time_head": after["time"],
                "time_change_pct": percentage(before["time"], after["time"]),
                "time_unit": after["time_unit"],
                "bytes_base": before["bytes_per_operation"],
                "bytes_head": after["bytes_per_operation"],
                "bytes_change_pct": percentage(
                    before["bytes_per_operation"], after["bytes_per_operation"]
                ),
            }
        )
    return {
        "rows": rows,
        "new": [head[key]["benchmark"] for key in sorted(set(head) - set(base), key=str)],
        "removed": [base[key]["benchmark"] for key in sorted(set(base) - set(head), key=str)],
    }


def format_number(value: float | None) -> str:
    return "—" if value is None else f"{value:,.1f}"


def format_percentage(value: float | None) -> str:
    if value is None:
        return "—"
    if math.isinf(value):
        return "+∞%"
    return f"{value:+.1f}%"


def markdown(report: dict) -> str:
    lines = [
        "## JMH A/B comparison",
        "",
        "Timing is advisory on shared runners. Allocation changes are usually more stable.",
        "",
        "| Benchmark | Parameters | Base time | Head time | Time change | Base B/op | Head B/op | B/op change |",
        "|---|---|---:|---:|---:|---:|---:|---:|",
    ]
    for row in report["rows"]:
        short_name = row["benchmark"].rsplit(".", 2)[-2] + "." + row["benchmark"].rsplit(".", 1)[-1]
        parameters = ", ".join(f"{key}={value}" for key, value in sorted(row["params"].items())) or "—"
        lines.append(
            "| `{}` | `{}` | {} {} | {} {} | {} | {} | {} | {} |".format(
                short_name,
                parameters,
                format_number(row["time_base"]),
                row["time_unit"],
                format_number(row["time_head"]),
                row["time_unit"],
                format_percentage(row["time_change_pct"]),
                format_number(row["bytes_base"]),
                format_number(row["bytes_head"]),
                format_percentage(row["bytes_change_pct"]),
            )
        )
    if not report["rows"]:
        lines.append("| _No paired benchmarks_ | — | — | — | — | — | — | — |")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    subcommands = parser.add_subparsers(dest="command", required=True)
    subcommands.add_parser("affected")
    subcommands.add_parser("validate-mapping")
    diff_parser = subcommands.add_parser("diff")
    diff_parser.add_argument("base", type=pathlib.Path)
    diff_parser.add_argument("head", type=pathlib.Path)
    diff_parser.add_argument("--output", "-o", required=True, type=pathlib.Path)
    markdown_parser = subcommands.add_parser("markdown")
    markdown_parser.add_argument("report", type=pathlib.Path)
    args = parser.parse_args()

    if args.command == "affected":
        print(affected(sys.stdin.readlines()))
        return 0
    if args.command == "validate-mapping":
        errors = validate_mapping(pathlib.Path.cwd())
        if errors:
            print("\n".join(errors), file=sys.stderr)
            return 1
        return 0
    if args.command == "diff":
        report = compare(load_results(args.base), load_results(args.head))
        args.output.write_text(json.dumps(report, indent=2) + "\n")
        return 0
    if args.command == "markdown":
        print(markdown(json.loads(args.report.read_text())), end="")
        return 0
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
