"""Runs the named mutants of a module: each one breaks one rule, and at least one law must fail.

Usage:
    python3 scripts/mutants/mutants.py modules/harness-kernel/src/test/mutants.toml [--only TEXT]

The file lists the mutants (see modules/harness-kernel/src/test/mutants.toml). For each mutant, the script applies its
edits, runs `sbt <project>/test`, and restores the code, also after an error or an interrupt. A mutant is killed
when a law fails, and it survives when all laws pass. A mutant that does not compile is an error in the list.

The script writes the results as a JUnit report, modules/<module>/target/test-reports/TEST-mutants.xml, with one test
case for each mutant. The blueprint reads that report like the other test reports. The exit code is 1 when a mutant
survives or does not compile. It needs Python 3.12 and sbt.
"""

import argparse
import re
import subprocess
import time
import tomllib
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


@dataclass
class Result:
    name: str
    status: str  # "killed", "survived" or "error"
    detail: str
    seconds: float


def apply(sources: Path, mutant: dict) -> tuple[dict[Path, str], str | None]:
    """Applies the edits of a mutant. It gives the original texts, and a problem when an old text does not occur
    exactly once. After a problem, the files are as before."""
    originals: dict[Path, str] = {}
    for edit in mutant["edit"]:
        path = sources / edit["file"]
        current = path.read_text()
        originals.setdefault(path, current)
        if (count := current.count(edit["old"])) != 1:
            restore(originals)
            return {}, f"{edit['file']}: the old text occurs {count} times, not once: {edit['old']!r}"
        path.write_text(current.replace(edit["old"], edit["new"]))
    return originals, None


def restore(originals: dict[Path, str]) -> None:
    for path, text in originals.items():
        path.write_text(text)


def run_tests(project: str) -> str:
    done = subprocess.run(["sbt", "-batch", f"{project}/test"], cwd=ROOT, capture_output=True, text=True)
    return done.stdout + done.stderr


def run_one(project: str, sources: Path, mutant: dict) -> Result:
    originals, problem = apply(sources, mutant)
    if problem:
        return Result(mutant["name"], "error", problem, 0.0)
    start = time.monotonic()
    try:
        output = run_tests(project)
    finally:
        restore(originals)
    seconds = time.monotonic() - start
    total = re.search(r"(Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+)", output)
    if "Compilation failed" in output or total is None:
        errors = [line for line in output.splitlines() if "[error]" in line][:5]
        return Result(mutant["name"], "error", "the mutant does not compile: " + " | ".join(errors), seconds)
    failed = int(total.group(3)) + int(total.group(4))
    if failed == 0:
        return Result(mutant["name"], "survived", f"all {total.group(2)} tests pass", seconds)
    laws = sorted(set(re.findall(r"==> X \S+?\.(\w+)\.(.+?)\s+\d", re.sub(r"\x1b\[[0-9;]*m", "", output))))
    return Result(mutant["name"], "killed", f"{failed} tests fail: " + "; ".join(t for _, t in laws[:3]), seconds)


def write_report(module: str, results: list[Result]) -> Path:
    suite = ET.Element("testsuite", name=f"{module}.mutants", tests=str(len(results)),
                       failures=str(sum(r.status == "survived" for r in results)),
                       errors=str(sum(r.status == "error" for r in results)))
    for r in results:
        case = ET.SubElement(suite, "testcase", classname=f"{module}.mutants", name=r.name, time=f"{r.seconds:.1f}")
        if r.status == "survived":
            ET.SubElement(case, "failure", message=r.detail)
        elif r.status == "error":
            ET.SubElement(case, "error", message=r.detail)
    path = ROOT / "modules" / module / "target/test-reports/TEST-mutants.xml"
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)
    return path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("mutants", type=Path, help="the TOML file with the mutants")
    parser.add_argument("--only", help="run only the mutants whose name holds this text")
    parser.add_argument("--check", action="store_true", help="only check that each edit applies, with no sbt run")
    args = parser.parse_args()
    data = tomllib.loads((ROOT / args.mutants).read_text())
    module, project, sources = data["module"], data["project"], ROOT / data["sources"]
    mutants = [m for m in data["mutant"] if not args.only or args.only in m["name"]]
    if args.check:
        problems = []
        for m in mutants:
            originals, problem = apply(sources, m)
            restore(originals)
            if problem:
                problems.append(f"{m['name']}: {problem}")
        print(f"{len(mutants) - len(problems)} of {len(mutants)} mutants apply.", *problems, sep="\n- ")
        raise SystemExit(1 if problems else 0)
    # A kill means something only when the laws pass on the code with no mutant.
    baseline = run_tests(project)
    if not re.search(r"Passed: Total \d+, Failed 0, Errors 0", baseline):
        tail = "\n".join(baseline.splitlines()[-15:])
        raise SystemExit(f"the laws of {module} do not pass with no mutant, so no mutant can run. sbt said:\n{tail}")
    results = []
    for m in mutants:
        result = run_one(project, sources, m)
        results.append(result)
        print(f"{result.status:8} {result.name} ({result.seconds:.0f} s): {result.detail}", flush=True)
    # The last run had a mutant, so its test reports are not the reports of the code. A run on the restored code
    # gives them again, and it shows that the restore worked.
    if not re.search(r"Passed: Total \d+, Failed 0, Errors 0", run_tests(project)):
        raise SystemExit(f"the laws of {module} fail after the restore of the code: look at the files of {sources}")
    path = write_report(module, results)
    killed = sum(r.status == "killed" for r in results)
    print(f"{killed} of {len(results)} mutants killed. The report is {path.relative_to(ROOT)}.")
    if killed != len(results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
