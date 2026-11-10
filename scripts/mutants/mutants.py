"""Runs the named mutants of a module: each one breaks one rule, and at least one law must fail.

Usage:
    python3 scripts/mutants/mutants.py modules/harness-kernel/src/test/mutants.toml [--only TEXT ...] [--timeout SECONDS]

The file lists the mutants (see modules/harness-kernel/src/test/mutants.toml). For each mutant, the script applies its
edits, runs `sbt <project>/test`, and restores the code, also after an error or an interrupt. A mutant is killed
when a law fails, and it survives when all laws pass. A mutant that does not compile is an error in the list. The
tests of one mutant have a time limit (300 s by default): past it, the script stops the whole sbt run, also the JVM of
the tests, and the mutant is killed. After a SIGKILL of the script itself, a mutant can stay in the code, and `--check`
shows it, because the old text of that mutant is gone.

The script writes the results as a JUnit report, modules/<module>/target/test-reports/TEST-mutants.xml, with one test
case for each mutant. With `--only`, the report keeps the earlier results of the other mutants, so a long list can run
in parts. The blueprint reads that report like the other test reports. The exit code is 1 when a mutant survives or
does not compile. It needs Python 3.12 and sbt.
"""

import argparse
import re
import os
import signal
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


def run_tests(project: str, timeout: float | None = None) -> tuple[str, bool]:
    """The output of the tests of `project`, and true when they ran past `timeout` seconds. The tests run in a process
    group of their own. Past the time, and after an interrupt of this script, the whole group stops, also the JVM that
    sbt forks for the tests, so no sbt run stays behind."""
    process = subprocess.Popen(["sbt", "-batch", f"{project}/test"], cwd=ROOT, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, start_new_session=True)
    try:
        output, _ = process.communicate(timeout=timeout)
        return output, False
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        output, _ = process.communicate()
        return output, True
    except BaseException:
        os.killpg(process.pid, signal.SIGKILL)
        raise


def run_one(project: str, sources: Path, mutant: dict, timeout: float) -> Result:
    originals, problem = apply(sources, mutant)
    if problem:
        return Result(mutant["name"], "error", problem, 0.0)
    start = time.monotonic()
    try:
        output, late = run_tests(project, timeout)
    finally:
        restore(originals)
    seconds = time.monotonic() - start
    # A law that never ends does not pass, so a mutant past the time is killed, as in other mutation tools.
    if late:
        return Result(mutant["name"], "killed", f"the tests ran past the limit of {timeout:.0f} s", seconds)
    total = re.search(r"(Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+)", output)
    if "Compilation failed" in output or total is None:
        errors = [line for line in output.splitlines() if "[error]" in line][:5]
        return Result(mutant["name"], "error", "the mutant does not compile: " + " | ".join(errors), seconds)
    failed = int(total.group(3)) + int(total.group(4))
    if failed == 0:
        return Result(mutant["name"], "survived", f"all {total.group(2)} tests pass", seconds)
    laws = sorted(set(re.findall(r"==> X \S+?\.(\w+)\.(.+?)\s+\d", re.sub(r"\x1b\[[0-9;]*m", "", output))))
    return Result(mutant["name"], "killed", f"{failed} tests fail: " + "; ".join(t for _, t in laws[:3]), seconds)


def write_report(module: str, results: list[Result], names: list[str], merge: bool) -> Path:
    """Writes the report of `results`. With `merge`, the report also keeps each earlier result of a mutant that did
    not run now, so runs with `--only` give one report of the whole list. `names` is the whole list, in its order, and
    a result of a name that is not in it is gone."""
    path = ROOT / "modules" / module / "target/test-reports/TEST-mutants.xml"
    cases: dict[str, ET.Element] = {}
    if merge and path.exists():
        cases = {c.get("name", ""): c for c in ET.parse(path).getroot().iter("testcase") if c.get("name") in names}
    for r in results:
        case = ET.Element("testcase", classname=f"{module}.mutants", name=r.name, time=f"{r.seconds:.1f}")
        if r.status == "survived":
            ET.SubElement(case, "failure", message=r.detail)
        elif r.status == "error":
            ET.SubElement(case, "error", message=r.detail)
        cases[r.name] = case
    ordered = [cases[n] for n in names if n in cases]
    suite = ET.Element("testsuite", name=f"{module}.mutants", tests=str(len(ordered)),
                       failures=str(sum(c.find("failure") is not None for c in ordered)),
                       errors=str(sum(c.find("error") is not None for c in ordered)))
    suite.extend(ordered)
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)
    return path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("mutants", type=Path, help="the TOML file with the mutants")
    parser.add_argument("--only", action="append",
                        help="run only the mutants whose name holds this text; give it more than once for more texts")
    parser.add_argument("--check", action="store_true", help="only check that each edit applies, with no sbt run")
    parser.add_argument("--timeout", type=float, default=300.0, help="the seconds of the tests of one mutant")
    args = parser.parse_args()
    data = tomllib.loads((ROOT / args.mutants).read_text())
    module, project, sources = data["module"], data["project"], ROOT / data["sources"]
    mutants = [m for m in data["mutant"] if not args.only or any(t in m["name"] for t in args.only)]
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
    baseline, late = run_tests(project, args.timeout)
    if late or not re.search(r"Passed: Total \d+, Failed 0, Errors 0", baseline):
        tail = "\n".join(baseline.splitlines()[-15:])
        raise SystemExit(f"the laws of {module} do not pass with no mutant, so no mutant can run. sbt said:\n{tail}")
    results = []
    for m in mutants:
        result = run_one(project, sources, m, args.timeout)
        results.append(result)
        print(f"{result.status:8} {result.name} ({result.seconds:.0f} s): {result.detail}", flush=True)
    # The last run had a mutant, so its test reports are not the reports of the code. A run on the restored code
    # gives them again, and it shows that the restore worked.
    after, late = run_tests(project, args.timeout)
    if late or not re.search(r"Passed: Total \d+, Failed 0, Errors 0", after):
        raise SystemExit(f"the laws of {module} fail after the restore of the code: look at the files of {sources}")
    path = write_report(module, results, [m["name"] for m in data["mutant"]], merge=args.only is not None)
    killed = sum(r.status == "killed" for r in results)
    print(f"{killed} of {len(results)} mutants killed. The report is {path.relative_to(ROOT)}.")
    if killed != len(results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
