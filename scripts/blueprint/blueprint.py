"""Makes the blueprint page of the code harness: the plan as a graph, with the state of each node.

Usage:
    python3 scripts/blueprint/blueprint.py [--artifact PATH] [--strict]

The script reads docs/code-harness/blueprint.toml, and the Scala sources and the test reports of the modules of each
group. It writes docs/code-harness/CODE_HARNESS_BLUEPRINT.html. It needs Graphviz (`dot`) and Python 3.12. With
--artifact, it also writes the page in the form that the artifact host takes, with no doctype, html, head or body.
With --strict, it stops with an error when the check of the code finds a problem.

Each node has two states, as in a Lean blueprint:
- The statement (the border): a type or function is stated when its declaration is in the code. A law is stated when
  a test with its name is in the code. A node is ready when each node that it uses is stated.
- The proof (the fill): a type is done when it and its methods have bodies, a function when it has a body that is not
  `???`, and a law when its tests pass in the test reports: a report passes each test, and no report fails one. A
  skip is no run, so a skip on macOS and a pass on Linux is a pass. A node is ready when it is stated and each node that
  its proof needs is complete. A node is complete when it and each node that its proof needs, and so on, are done.

As in a Lean blueprint, the statement and the proof have different uses. `uses` holds what the statement needs: the
types in the fields and the signature. The proof needs the code that it runs: the functions in `uses`, and the nodes
in `proof_uses`, for example a type whose method a body calls. A test runs what it uses, so the proof of a law or a
check also needs each node in `uses` that has a proof. A type with no methods has no proof, so it is done when it is
stated.

The waves show the work that can run at the same time. A node that is not stated has a statement wave: 1 when each
node that it uses is stated, and else 1 more than the highest statement wave of those nodes. The proof wave is the
same for the proofs, over what each proof needs. The nodes of one wave do not use each other, so one writer for each
group can work on them at the same time. The highest wave is the length of the longest chain.

The check of the code compares the uses of each type and function with its code. It reads names, not types, so it
gives warnings and not errors:
- The code names a node, or calls a method of a node, that the node does not use, directly or through other nodes.
  The plan then shows parallel work that is not parallel. A method counts when its name follows a dot, apart from
  names that collections also have, for example `map`.
- A done node uses a node that its code does not name, and no other path gives that use. The plan then shows an
  order that the code does not need.
- The code of a node is not in the file of its group.
"""

import argparse
import html
import json
import re
import subprocess
import tomllib
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs/code-harness/blueprint.toml"
TEMPLATE = Path(__file__).with_name("page.html")
OUTPUT = ROOT / "docs/code-harness/CODE_HARNESS_BLUEPRINT.html"
KINDS = {"upstream", "data", "function", "law", "check"}
PLACES = {"main", "tests"}


@dataclass
class Phase:
    id: str
    number: int
    title: str
    modules: list[str]
    uses: list[str]
    result: str
    partial: bool = False


@dataclass
class Group:
    id: str
    phase: str
    module: str
    file: str
    title: str
    where: str = "main"


@dataclass
class Node:
    id: str
    kind: str
    name: str
    ref: str
    uses: list[str]
    group: str | None = None
    text: str = ""
    ops: list[str] = field(default_factory=list)
    proof_uses: list[str] = field(default_factory=list)
    spec: bool = False
    reviewed: str | None = None
    blocked: str | None = None
    # From the group:
    phase: str | None = field(default=None, init=False)
    where: str = field(default="main", init=False)
    # Found by the scan, the states and the checks:
    stated: bool = field(default=False, init=False)
    done: bool = field(default=False, init=False)
    file: str | None = field(default=None, init=False)
    code: str = field(default="", init=False)
    statement: str = field(default="", init=False)
    proof: str = field(default="", init=False)
    why_statement: str = field(default="", init=False)
    why_proof: str = field(default="", init=False)
    swave: int = field(default=0, init=False)
    pwave: int = field(default=0, init=False)
    notes: list[str] = field(default_factory=list, init=False)

    @property
    def title(self) -> str:
        return self.name.rstrip(":") if self.kind in ("law", "check") else self.name


@dataclass
class Code:
    main: dict[str, str]
    tests: dict[str, str]
    results: dict[str, bool]

    def files(self, where: str) -> dict[str, str]:
        return self.tests if where == "tests" else self.main


# ── The source file ──────────────────────────────────────────────────────────────────────────────────────────────


def load(path: Path) -> tuple[dict, list[Phase], list[Group], list[Node]]:
    data = tomllib.loads(path.read_text())
    try:
        phases = [Phase(**p) for p in data["phase"]]
        groups = [Group(**g) for g in data.get("group", [])]
        nodes = [Node(**n) for n in data["node"]]
    except TypeError as e:
        raise SystemExit(f"blueprint.toml has an unknown field, or a field is missing: {e}") from None
    check(phases, groups, nodes)
    by_group = {g.id: g for g in groups}
    for n in nodes:
        if n.group:
            n.phase, n.where = by_group[n.group].phase, by_group[n.group].where
    return data["meta"], phases, groups, nodes


def check(phases: list[Phase], groups: list[Group], nodes: list[Node]) -> None:
    """Stops with a clear message when the source file has a mistake."""
    problems: list[str] = []
    by_phase = {p.id: p for p in phases}
    by_group = {g.id: g for g in groups}
    node_ids = [n.id for n in nodes]
    for kind, ids in (("phase", [p.id for p in phases]), ("group", [g.id for g in groups]), ("node", node_ids)):
        twice = sorted({i for i in ids if ids.count(i) > 1})
        if twice:
            problems.append(f"each {kind} id must be unique: {', '.join(twice)}")
    for p in phases:
        problems += [f"phase {p.id} uses the unknown phase {u}" for u in p.uses if u not in by_phase]
    files: dict[tuple[str, str, str], str] = {}
    for g in groups:
        if g.phase not in by_phase:
            problems.append(f"group {g.id} has the unknown phase {g.phase}")
        elif g.module not in by_phase[g.phase].modules:
            problems.append(f"group {g.id} has the module {g.module}, which is not a module of phase {g.phase}")
        if g.where not in PLACES:
            problems.append(f"group {g.id} has the unknown place {g.where}; use \"main\" or \"tests\"")
        if (key := (g.module, g.where, g.file)) in files:
            problems.append(f"the groups {files[key]} and {g.id} have the same file {g.file}")
        files[key] = g.id
    for n in nodes:
        if n.kind not in KINDS:
            problems.append(f"node {n.id} has the unknown kind {n.kind}")
        if n.kind == "upstream":
            if n.group:
                problems.append(f"the upstream node {n.id} must have no group")
        elif n.group is None:
            problems.append(f"node {n.id} needs a group")
        elif n.group not in by_group:
            problems.append(f"node {n.id} has the unknown group {n.group}")
        elif n.kind in ("law", "check") and by_group[n.group].where != "tests":
            problems.append(f"the {n.kind} {n.id} must be in a group of test code")
        problems += [f"node {n.id} uses the unknown node {u}" for u in n.uses + n.proof_uses if u not in node_ids]
        if n.kind in ("law", "check") and not n.name.endswith(":"):
            problems.append(f"the name of the {n.kind} {n.id} must end with a colon, for example \"Fold:\"")
    if not problems:
        graphs = {
            "uses of the nodes": {n.id: n.uses + n.proof_uses for n in nodes},
            "uses of the phases": {p.id: p.uses for p in phases},
            "groups (move a node to another group)": group_uses(groups, nodes),
        }
        for what, edges in graphs.items():
            if cycle := next(filter(None, (find_cycle(k, edges) for k in edges)), None):
                problems.append(f"the {what} make a cycle: " + " -> ".join(cycle))
    if problems:
        raise SystemExit("blueprint.toml has problems:\n- " + "\n- ".join(problems))


def find_cycle(start: str, uses: dict[str, list[str]]) -> list[str] | None:
    path: list[str] = []
    def visit(node: str) -> list[str] | None:
        if node in path:
            return path[path.index(node):] + [node]
        path.append(node)
        for u in uses.get(node, []):
            if found := visit(u):
                return found
        path.pop()
        return None
    return visit(start)


def has_proof(n: Node) -> bool:
    """True for a node with code to prove: a function, a law, a check, or a type with methods."""
    return n.kind in ("function", "law", "check") or (n.kind == "data" and bool(n.ops))


def proof_needs(n: Node, by_id: dict[str, Node]) -> list[str]:
    """The nodes whose code the proof of `n` runs: the functions in `uses`, the nodes in `proof_uses`, and, for a law
    or a check, each node in `uses` that has a proof, because a test runs what it uses. A type in a signature or a
    field is part of the statement only."""
    if not has_proof(n):
        return []
    test = n.kind in ("law", "check")
    stated = [u for u in n.uses if has_proof(by_id[u]) and (test or by_id[u].kind == "function")]
    return stated + [u for u in n.proof_uses if u not in stated]


def group_uses(groups: list[Group], nodes: list[Node]) -> dict[str, list[str]]:
    """For each group, the other groups whose nodes its nodes use."""
    group_of = {n.id: n.group for n in nodes}
    out: dict[str, list[str]] = {g.id: [] for g in groups}
    for n in nodes:
        if n.group in out:
            for u in n.uses + n.proof_uses:
                g = group_of.get(u)
                if g and g != n.group and g not in out[n.group]:
                    out[n.group].append(g)
    return out


def closure(edges: dict[str, list[str]]) -> dict[str, set[str]]:
    """For each key, all the keys under it."""
    memo: dict[str, set[str]] = {}
    def below(x: str) -> set[str]:
        if x not in memo:
            memo[x] = set()
            for u in edges.get(x, []):
                memo[x] |= {u} | below(u)
        return memo[x]
    for x in edges:
        below(x)
    return memo


# ── The scan of the code and the test reports ────────────────────────────────────────────────────────────────────


def read_code(module: str) -> Code:
    base = ROOT / "modules" / module
    def texts(rel: str, pattern: str = "*.scala") -> dict[str, str]:
        if not (base / rel).is_dir():
            return {}
        return {str(f.relative_to(ROOT)): f.read_text() for f in sorted((base / rel).rglob(pattern))}

    # A test passes when a report has it with no failure and no report has it with a failure. A skip counts as no run,
    # so a test that skips on macOS and passes in the Linux report of scripts/sandbox-linux/run.sh passes.
    passed: set[str] = set()
    failed: set[str] = set()
    seen: set[str] = set()
    if (reports := base / "target/test-reports").is_dir():
        for report in sorted(reports.glob("*.xml")):
            for case in ET.parse(report).getroot().iter("testcase"):
                tags = {child.tag for child in case}
                name = case.get("name", "")
                seen.add(name)
                if tags & {"failure", "error"}:
                    failed.add(name)
                elif "skipped" not in tags:
                    passed.add(name)
    results = {name: name in passed and name not in failed for name in seen}
    # A check can have its statement in a list of the tests, for example the mutants of the kernel in mutants.toml.
    return Code(texts("src/main/scala"), texts("src/test/scala") | texts("src/test", "*.toml"), results)


def type_pattern(name: str) -> re.Pattern[str]:
    """A declaration of the type `name`, with its annotations and modifiers, for example `@assumeSafe final class`."""
    annotations = r"(?:@[\w.]+(?:\([^)\n]*\))?\s+)*"
    return re.compile(
        rf"^([ \t]*){annotations}(?:[a-z ]*\s)?(?:enum|class|trait|object|type)\s+{re.escape(name)}(?![\w$])", re.M)


def has_body(text: str, start: int) -> bool:
    """True when the definition that starts at `start` has a body that is not `???`."""
    line_start = text.rfind("\n", 0, start) + 1
    indent = len(text[line_start:start]) - len(text[line_start:start].lstrip())
    depth, i = 0, start
    while i < len(text):
        c = text[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "=" and depth == 0 and text[i - 1] not in "=<>!" and text[i + 1 : i + 2] not in ("=", ">"):
            return not text[i + 1 :].lstrip().startswith("???")
        elif c == "\n" and depth == 0:
            rest = text[i + 1 :]
            following = next((line for line in rest.split("\n") if line.strip()), "")
            if len(following) - len(following.lstrip()) <= indent:
                return False  # an abstract definition: the signature ends with no body
        i += 1
    return False


def find_def(text: str, name: str, vals: bool = False) -> list[int]:
    """The places of each `def name`, and of each `val name` when `vals` is true. A method of a type can be a val."""
    keyword = r"(?:def|val)" if vals else "def"
    return [m.start() for m in re.finditer(rf"\b{keyword}\s+{re.escape(name)}(?=[\s(\[:=])", text)]


def block_end(text: str, body: int, indent: int) -> int:
    """The end of a block whose body starts at `body`: the next line with code at `indent` or less."""
    for line in re.finditer(r"^( *)([^\s)\]}])", text[body:], re.M):  # a closing bracket does not end it
        if len(line.group(1)) <= indent:
            return body + line.start()
    return len(text)


def block_of(text: str, name: str) -> str:
    """The text of each declaration of the type `name` and of its companion object."""
    parts = []
    for m in type_pattern(name).finditer(text):
        body = text.find("\n", m.end()) + 1 or len(text)  # the block ends on a later line, not on this one
        parts.append(text[m.start() : block_end(text, body, len(m.group(1)))])
    return "\n".join(parts)


def def_block(text: str, start: int) -> str:
    """The text of the definition that starts at `start`."""
    line_start = text.rfind("\n", 0, start) + 1
    indent = len(text[line_start:start]) - len(text[line_start:start].lstrip())
    body = text.find("\n", start) + 1 or len(text)
    return text[start : block_end(text, body, indent)]


def with_helpers(text: str, code: str, skip: set[str]) -> str:
    """`code` with the helpers in the same file that it calls, and theirs: the defs, classes and objects whose name is
    not the name of a node. So the check of a function also reads its private helpers."""
    seen: set[str] = set()
    parts, todo = [code], [code]
    while todo:
        body = strip(todo.pop())
        for m in re.finditer(r"(?<![\w$.])([A-Za-z_][\w$]*)\s*[(\[]", body):
            name = m.group(1)
            # A name after `def` declares a method, for example of an anonymous class, and calls nothing.
            if name in seen or name in skip or body[: m.start()].rstrip().endswith("def"):
                continue
            seen.add(name)
            blocks = [def_block(text, p) for p in find_def(text, name)]
            blocks += [block] if (block := block_of(text, name)) else []
            parts += blocks
            todo += blocks
    return "\n".join(parts)


def scan(node: Node, code: Code | None, names: set[str]) -> None:
    if node.kind == "upstream" or code is None:
        node.stated = node.done = node.kind == "upstream"
    elif node.kind == "data":
        hits = [f for f, t in code.main.items() if type_pattern(node.name).search(t)]
        node.stated, node.file = bool(hits), (hits[0] if hits else None)
        node.code = "\n".join(b for t in code.main.values() if (b := block_of(t, node.name)))
        node.done = node.stated and all(
            any(has_body(node.code, p) for p in find_def(node.code, op, vals=True)) for op in node.ops)
    elif node.kind == "function":
        found = [(f, t, p) for f, t in code.files(node.where).items() for p in find_def(t, node.name)]
        node.stated, node.file = bool(found), (found[0][0] if found else None)
        node.code = "\n".join(with_helpers(t, def_block(t, p), names) for _, t, p in found)
        node.done = any(has_body(t, p) for _, t, p in found)
    else:  # a law or a check: the tests are the statement
        hits = [f for f, t in code.tests.items() if f'"{node.name}' in t]
        node.stated, node.file = bool(hits), (hits[0] if hits else None)
        runs = [ok for name, ok in code.results.items() if name.startswith(node.name)]
        node.done = node.stated and bool(runs) and all(runs)


# ── The states and the waves ─────────────────────────────────────────────────────────────────────────────────────


def names(ids: list[str], by_id: dict[str, Node]) -> str:
    return ", ".join(by_id[i].title for i in ids)


def derive(nodes: list[Node]) -> None:
    by_id = {n.id: n for n in nodes}
    for n in topological(nodes):
        deps = [by_id[u] for u in n.uses]
        unstated = [d.id for d in deps if d.statement not in ("stated", "upstream")]
        if n.kind == "upstream":
            n.statement, n.why_statement = "upstream", "It comes from a library."
        elif n.stated:
            n.statement = "stated"
            n.why_statement = {
                "data": "The type is in the code.",
                "function": "The function is in the code.",
            }.get(n.kind, "A test with this name is in the code.")
        elif n.blocked:
            n.statement, n.why_statement = "blocked", f"Blocked: {n.blocked}"
        elif not unstated:
            n.statement = "ready"
            n.why_statement = "Each node that it uses is stated." if deps else "It uses no other node."
        else:
            n.statement = "waiting"
            n.why_statement = f"These nodes are not stated yet: {names(unstated, by_id)}."

        needs = [by_id[u] for u in proof_needs(n, by_id)]
        not_complete = [d.id for d in needs if d.proof != "complete"]
        if n.kind == "upstream":
            n.proof, n.why_proof = "complete", "It comes from a library."
        elif n.done:
            n.proof = "done" if not_complete else "complete"
            what = {
                "data": "The type and its methods have bodies." if n.ops else "The type has no methods to prove.",
                "function": "The function has a body.",
            }.get(n.kind, "Its tests pass.")
            under = (f" These nodes that its proof needs are not complete: {names(not_complete, by_id)}."
                     if not_complete else " Each node that its proof needs is complete." if needs else "")
            n.why_proof = what + under
        elif n.stated and n.blocked:
            n.proof, n.why_proof = "none", f"Blocked: {n.blocked}"
        elif n.stated and not not_complete:
            n.proof = "ready"
            n.why_proof = ("Each node that its proof needs is complete, so the work can start." if needs
                           else "Its proof needs no other node, so the work can start.")
        else:
            n.proof = "none"
            n.why_proof = (
                "The statement comes first." if not n.stated
                else f"These nodes that its proof needs are not complete yet: {names(not_complete, by_id)}."
            )

        n.swave = 0 if n.stated or n.kind == "upstream" else 1 + max((d.swave for d in deps), default=0)
        n.pwave = 0 if n.done or n.kind == "upstream" else 1 + max((d.pwave for d in needs), default=0)


def topological(nodes: list[Node]) -> list[Node]:
    by_id = {n.id: n for n in nodes}
    seen: set[str] = set()
    out: list[Node] = []
    def visit(n: Node) -> None:
        if n.id in seen:
            return
        seen.add(n.id)
        for u in n.uses + n.proof_uses:
            visit(by_id[u])
        out.append(n)
    for n in nodes:
        visit(n)
    return out


@dataclass
class PhaseState:
    total: int = 0
    stated: int = 0
    done: int = 0
    spec: int = 0
    reviewed: int = 0
    statement: str = "waiting"
    proof: str = "none"
    why: str = ""
    wave: int = 0


def derive_phases(phases: list[Phase], nodes: list[Node]) -> dict[str, PhaseState]:
    by_id = {p.id: p for p in phases}
    order: list[Phase] = []
    def visit(p: Phase) -> None:
        if p not in order:
            for u in p.uses:
                visit(by_id[u])
            order.append(p)
    for p in sorted(phases, key=lambda p: p.number):
        visit(p)
    states: dict[str, PhaseState] = {}
    for p in order:
        own = [n for n in nodes if n.phase == p.id]
        s = PhaseState(
            total=len(own),
            stated=sum(n.stated for n in own),
            done=sum(n.done for n in own),
            spec=sum(n.spec for n in own),
            reviewed=sum(bool(n.reviewed) for n in own if n.spec),
        )
        deps_complete = all(states[u].proof == "complete" for u in p.uses)
        if own and s.done == s.total and not p.partial:
            s.statement, s.proof = "stated", "complete" if deps_complete else "done"
            s.why = "Each node of the phase is done."
        elif own and s.stated == s.total and not p.partial:
            s.statement, s.proof = "stated", "ready" if deps_complete else "none"
            s.why = "Each node of the phase is stated."
        elif deps_complete:
            s.statement = "ready"
            s.why = ("The phases that it uses are complete, so the work can start." if p.uses
                     else "It uses no other phase, so the work can start.")
            if not own:
                s.why += " The phase has no nodes in the blueprint yet."
            elif p.partial:
                s.why += " The blueprint has only some nodes of the phase."
        else:
            s.statement = "waiting"
            s.why = "These phases are not complete yet: " + ", ".join(
                f"phase {by_id[u].number}" for u in p.uses if states[u].proof != "complete") + "."
        s.wave = 0 if s.proof == "complete" else 1 + max((states[u].wave for u in p.uses), default=0)
        states[p.id] = s
    return states


def partial_uses(phases: list[Phase], nodes: list[Node]) -> dict[tuple[str, str], list[str]]:
    """The nodes that a phase uses from a phase that it does not use: (from, to) -> the node ids."""
    full = closure({p.id: p.uses for p in phases})
    by_id = {n.id: n for n in nodes}
    out: dict[tuple[str, str], list[str]] = {}
    for n in nodes:
        for u in n.uses + n.proof_uses:
            d = by_id[u]
            if n.phase and d.phase and d.phase != n.phase and d.phase not in full[n.phase]:
                ids = out.setdefault((d.phase, n.phase), [])
                if d.id not in ids:
                    ids.append(d.id)
    return out


def outside(phase: Phase, phases: list[Phase], nodes: list[Node]) -> list[str]:
    """The nodes of other phases that the graph of `phase` shows: each node that a node of the phase uses, and the
    nodes under it in a phase that `phase` does not use."""
    full = closure({p.id: p.uses for p in phases})[phase.id]
    by_id = {n.id: n for n in nodes}
    queue = [u for n in nodes if n.phase == phase.id for u in n.uses + n.proof_uses if by_id[u].phase != phase.id]
    direct = set(queue)
    out: list[str] = []
    while queue:
        u = queue.pop(0)
        if u in out:
            continue
        x = by_id[u]
        partial = x.phase is not None and x.phase not in full and x.phase != phase.id
        if u in direct or partial:
            out.append(u)
        if partial:
            queue += x.uses + x.proof_uses
    return out


# ── The check of the code ────────────────────────────────────────────────────────────────────────────────────────


def strip(code: str) -> str:
    """The code with no comments and no string literals."""
    code = re.sub(r'"""[\s\S]*?"""', '""', code)
    code = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', code)
    code = re.sub(r"/\*[\s\S]*?\*/", " ", code)
    return re.sub(r"//[^\n]*", " ", code)


def named(code: str, by_name: dict[str, Node]) -> set[str]:
    """The ids of the nodes that the code names. A function counts only in a call, or as an argument. A name after a
    dot is a member, for example the case `EffectResult.NeedsApproval`, so it does not count."""
    text = strip(code)
    out: set[str] = set()
    for m in re.finditer(r"(?<![\w$.])[A-Za-z_][\w$]*", text):
        n = by_name.get(m.group())
        if n is None:
            continue
        line_start = text.rfind("\n", 0, m.start()) + 1
        line_end = text.find("\n", m.end())
        line = text[line_start : line_end if line_end >= 0 else len(text)]
        head = line.split("(")[0]  # the names before the fields, for example `case Once, Turn, Session`
        if line.lstrip().startswith("case ") and "=>" not in line and m.start() - line_start < len(head):
            continue  # the declaration of an enum case, not a use
        if n.kind == "function":
            after = text[m.end():].lstrip()[:1]
            before = text[:m.start()].rstrip()[-1:]
            if not (after in ("(", "[") or (before in ("(", ",") and after in (")", ","))):
                continue
        out.add(n.id)
    return out


# Names that collections, the session state or the guest API also have, so a call of one tells nothing.
COMMON_METHODS = {"apply", "map", "flatMap", "zip", "filter", "fold", "foldLeft", "get", "contains", "of", "join",
                  "size", "keys", "store", "read", "write", "find", "update", "parse", "sync", "matches"}


def called(code: str, owners: dict[str, Node]) -> dict[str, str]:
    """The nodes whose methods the code calls, as id -> the first method. A method counts after a dot, apart from a
    name that the code itself declares as a field, a parameter or a member, for example the `initial` of `Fold`."""
    text = strip(code)
    own = set(re.findall(r"\b([A-Za-z_][\w$]*)\s*:(?!:)", text))
    own |= set(re.findall(r"\b(?:def|val|var)\s+([A-Za-z_][\w$]*)", text))
    out: dict[str, str] = {}
    for m in re.finditer(r"\.\s*([A-Za-z_][\w$]*|[<>=!]+)", text):
        if m.group(1) not in own and (n := owners.get(m.group(1))) is not None:
            out.setdefault(n.id, m.group(1))
    return out


def check_code(nodes: list[Node], groups: list[Group]) -> None:
    by_id = {n.id: n for n in nodes}
    # A library type is complete, so a use of it never changes the order of the work. The check ignores it.
    by_name: dict[str, Node] = {}
    for n in nodes:
        if n.kind in ("data", "function"):
            by_name.setdefault(n.name, n)
    # A method name that two types share, or that collections also have, tells nothing.
    count: dict[str, int] = {}
    for n in nodes:
        for op in n.ops:
            count[op] = count.get(op, 0) + 1
    # A name that the code of another node declares, as a def, a val or a parameter, also tells nothing: a call of
    # `callPolicy` can go to the trait `TurnEffects`, and `writes` can be a field of `CellResult`.
    declared: dict[str, set[str]] = {}
    for n in nodes:
        if n.kind in ("data", "function") and n.stated:
            text = strip(n.code)
            names = set(re.findall(r"\b(?:def|val|var)\s+([A-Za-z_][\w$]*)", text))
            names |= set(re.findall(r"\b([A-Za-z_][\w$]*)\s*:(?!:)", text))
            for name in names:
                declared.setdefault(name, set()).add(n.id)
    owners = {op: n for n in nodes for op in n.ops
              if count[op] == 1 and op not in COMMON_METHODS and declared.get(op, {n.id}) <= {n.id}}
    # A type in the code must be under the node. A call runs code, so its node must be under the proof of the node.
    below = closure({n.id: n.uses + n.proof_uses for n in nodes})
    proof_below = closure({n.id: proof_needs(n, by_id) for n in nodes})
    only = set(reduced({n.id: n.uses for n in nodes}))
    group_of = {g.id: g for g in groups}
    for n in nodes:
        if n.kind in ("data", "function") and n.stated:
            uses = named(n.code, by_name) - {n.id}
            for d in sorted(uses):
                if by_id[d].kind == "function" and d not in proof_below[n.id]:
                    n.notes.append(f"The code calls {by_id[d].title}, but the proof of {n.title} does not use it. "
                                   f"Add {d} to its proof_uses, or the proof waves are wrong.")
                elif by_id[d].kind != "function" and d not in below[n.id]:
                    n.notes.append(f"The code names {by_id[d].title}, but {n.title} does not use it. "
                                   f"Add {d} to its uses, or the plan shows parallel work that is not parallel.")
            for d, op in sorted(called(n.code, owners).items()):
                if d != n.id and d not in proof_below[n.id]:
                    n.notes.append(f"The code calls {op} of {by_id[d].title}, but the proof of {n.title} does not "
                                   f"use it. Add {d} to its proof_uses, or the proof waves are wrong.")
            if n.done:
                for d in n.uses:
                    if (n.id, d) in only and d not in uses and by_id[d].kind != "upstream":
                        n.notes.append(f"{n.title} uses {by_id[d].title}, but its code does not name it. "
                                       f"Remove the use if {n.title} does not need it.")
        if n.file and n.group and Path(n.file).name != group_of[n.group].file:
            n.notes.append(f"The code is in {Path(n.file).name}, but the group {n.group} has the file "
                           f"{group_of[n.group].file}. Move the code, or change the group.")


# ── The drawing ──────────────────────────────────────────────────────────────────────────────────────────────────


def reduced(edges: dict[str, list[str]]) -> list[tuple[str, str]]:
    """The edges that no other path implies. The page draws only these, and the panel lists all uses."""
    def reach(start: str, skip: str) -> set[str]:
        seen: set[str] = set()
        stack = [u for u in edges.get(start, []) if u != skip]
        while stack:
            u = stack.pop()
            if u not in seen:
                seen.add(u)
                stack += edges.get(u, [])
        return seen
    return [(n, u) for n, uses in edges.items() for u in uses if u not in reach(n, u)]


SHAPES = {
    "upstream": 'shape=box, style="filled,dashed"',
    "data": 'shape=box, style="filled"',
    "function": 'shape=box, style="rounded,filled"',
    "law": 'shape=ellipse, style="filled"',
    "check": 'shape=hexagon, style="filled"',
    "phase": 'shape=box, style="rounded,filled", margin="0.18,0.10"',
}


def esc(s: str) -> str:
    return html.escape(s, quote=True)


def run_dot(lines: list[str]) -> str:
    source = "\n".join(lines)
    svg = subprocess.run(["dot", "-Tsvg"], input=source, capture_output=True, text=True, check=True).stdout
    svg = svg[svg.index("<svg"):]
    svg = re.sub(r"<!--.*?-->\n?", "", svg, flags=re.S)
    m = re.match(r'<svg width="([\d.]+)pt" height="([\d.]+)pt"', svg)
    width = round(float(m.group(1)) * 4 / 3) if m else 800
    svg = re.sub(r'^<svg width="[\d.]+pt" height="[\d.]+pt"', f'<svg data-width="{width}"', svg)
    return svg.replace("<svg ", '<svg role="img" ', 1)


def header(name: str) -> list[str]:
    return [
        f'digraph "{name}" {{',
        '  graph [rankdir=TB, nodesep=0.22, ranksep=0.45, bgcolor="transparent", pad=0.15];',
        '  node [fontname="Helvetica", fontsize=11, margin="0.10,0.04", penwidth=1.6,'
        ' color="black", fillcolor="white"];',
        '  edge [arrowsize=0.55, penwidth=0.9, color="gray"];',
    ]


def node_svg(own: list[Node], others: list[Node], numbers: dict[str, int]) -> str:
    lines = header("phase")
    ids = {n.id for n in own + others}
    outer = {n.id for n in others if n.kind != "upstream"}
    for n in own + others:
        ext = n.id in outer
        mark = " · spec ✓" if n.spec and n.reviewed else " · spec" if n.spec else ""
        sub = (f"phase {numbers[n.phase]} · " if ext and n.phase else "") + f"§{n.ref}{mark}"
        sub += " · tests" if n.where == "tests" and n.kind == "function" else ""
        label = (f'<<FONT POINT-SIZE="11"><B>{esc(n.title)}</B></FONT><BR/>'
                 f'<FONT POINT-SIZE="8">{esc(sub)}</FONT>>')
        classes = f"kind-{n.kind} st-{n.statement} pf-{n.proof}" + (" spec" if n.spec else "")
        classes += " ext" if ext else ""
        extra = ", peripheries=2" if n.spec else ""
        lines.append(f'  "{n.id}" [id="n-{n.id}", class="{classes}", label={label}, {SHAPES[n.kind]}{extra}];')
    for user, dep in reduced({n.id: [u for u in n.uses if u in ids] for n in own + others}):
        lines.append(f'  "{dep}" -> "{user}" [id="e-{dep}--{user}"];')
    # The extra uses of a proof, dashed as in a Lean blueprint. They do not change the layout.
    for n in own + others:
        for dep in n.proof_uses:
            if dep in ids:
                lines.append(f'  "{dep}" -> "{n.id}" [id="e-{dep}--{n.id}", class="proof", style=dashed];')
    lines.append("}")
    return run_dot(lines)


def plan_svg(phases: list[Phase], states: dict[str, PhaseState], partials: dict[tuple[str, str], list[str]],
             by_id: dict[str, Node]) -> str:
    lines = header("plan")
    for p in phases:
        s = states[p.id]
        modules = ", ".join(p.modules) if p.modules else "in the modules above"
        count = f"{s.stated} of {s.total} stated · {s.done} done" if s.total else "not in the blueprint yet"
        count += " · more to come" if s.total and p.partial else ""
        label = (f'<<FONT POINT-SIZE="12"><B>{p.number} · {esc(p.title)}</B></FONT><BR/>'
                 f'<FONT POINT-SIZE="9">{esc(modules)}</FONT><BR/><FONT POINT-SIZE="9">{esc(count)}</FONT>>')
        classes = f"kind-phase st-{s.statement} pf-{s.proof}"
        lines.append(f'  "{p.id}" [id="n-{p.id}", class="{classes}", label={label}, {SHAPES["phase"]}];')
    for p in phases:
        for u in p.uses:
            lines.append(f'  "{u}" -> "{p.id}" [id="e-{u}--{p.id}"];')
    for (a, b), ids in partials.items():
        label = esc(", ".join(by_id[i].title for i in ids))
        lines.append(f'  "{a}" -> "{b}" [id="e-{a}--{b}", class="partial", style=dotted, constraint=false,'
                     f' label=<<FONT POINT-SIZE="9">{label}</FONT>>];')
    # Each wave of the open work is one row. The complete phases have wave 0, and they can use each other, so they get
    # no row: a row would make flat edges between them, and dot fails on a flat edge with a label.
    waves: dict[int, list[str]] = {}
    for p in phases:
        if states[p.id].wave > 0:
            waves.setdefault(states[p.id].wave, []).append(f'"{p.id}"')
    lines += [f"  {{ rank=same; {'; '.join(ids)} }}" for ids in waves.values() if len(ids) > 1]
    lines.append("}")
    return run_dot(lines)


# ── The page ─────────────────────────────────────────────────────────────────────────────────────────────────────


def git(*args: str) -> str:
    try:
        done = subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True, check=True)
        return done.stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return ""


@dataclass
class Plan:
    meta: dict
    phases: list[Phase]
    groups: list[Group]
    nodes: list[Node]
    states: dict[str, PhaseState]
    partials: dict[tuple[str, str], list[str]]
    others: dict[str, list[str]]


def page_data(plan: Plan) -> dict:
    users: dict[str, list[str]] = {n.id: [] for n in plan.nodes}
    for n in plan.nodes:
        for u in n.uses + n.proof_uses:
            users[u].append(n.id)
    numbers = {p.id: p.number for p in plan.phases}
    shown = [p for p in plan.phases if any(n.phase == p.id for n in plan.nodes)]
    between = group_uses(plan.groups, plan.nodes)
    return {
        "title": plan.meta["title"],
        "commit": git("rev-parse", "--short", "HEAD"),
        "date": git("log", "-1", "--format=%cs"),
        "views": [{"id": "plan", "tab": "The plan"}] + [
            {"id": f"phase-{p.number}", "tab": f"Phase {p.number}: {p.title}", "phase": p.id,
             "outside": [f"n-{u}" for u in plan.others[p.id]]}
            for p in shown
        ],
        "phases": {
            f"n-{p.id}": {
                "title": f"Phase {p.number}: {p.title}", "number": p.number, "kind": "phase", "modules": p.modules,
                "result": p.result, "partial": p.partial, "uses": [f"n-{u}" for u in p.uses],
                "users": [f"n-{q.id}" for q in plan.phases if p.id in q.uses],
                "view": f"phase-{p.number}" if p in shown else None, **vars(plan.states[p.id]),
            }
            for p in plan.phases
        },
        "partials": [
            {"from": f"n-{a}", "to": f"n-{b}", "nodes": [f"n-{i}" for i in ids]}
            for (a, b), ids in plan.partials.items()
        ],
        "groups": {
            g.id: {"title": g.title, "file": g.file, "module": g.module, "where": g.where, "phase": g.phase,
                   "uses": between[g.id]}
            for g in plan.groups
        },
        "nodes": {
            f"n-{n.id}": {
                "title": n.title, "kind": n.kind, "phase": n.phase, "phaseNumber": numbers.get(n.phase or ""),
                "group": n.group, "ref": n.ref, "text": n.text, "spec": n.spec, "reviewed": n.reviewed,
                "blocked": n.blocked, "where": n.where, "ops": n.ops, "file": n.file,
                "statement": n.statement, "proof": n.proof, "whyStatement": n.why_statement, "whyProof": n.why_proof,
                "swave": n.swave, "pwave": n.pwave, "notes": n.notes,
                "uses": [f"n-{u}" for u in n.uses], "proofUses": [f"n-{u}" for u in n.proof_uses],
                "users": [f"n-{u}" for u in users[n.id]],
            }
            for n in plan.nodes
        },
    }


def render(plan: Plan) -> str:
    page = TEMPLATE.read_text()
    by_id = {n.id: n for n in plan.nodes}
    numbers = {p.id: p.number for p in plan.phases}
    svg = plan_svg(plan.phases, plan.states, plan.partials, by_id)
    views = [f'<div class="view-graph" id="graph-plan" data-view="plan">{svg}</div>']
    for p in plan.phases:
        own = [n for n in plan.nodes if n.phase == p.id]
        if own:
            others = [by_id[u] for u in plan.others[p.id]]
            views.append(f'<div class="view-graph" id="graph-phase-{p.number}" data-view="phase-{p.number}" hidden>'
                         f"{node_svg(own, others, numbers)}</div>")
    data = json.dumps(page_data(plan), ensure_ascii=False, indent=None).replace("</", "<\\/")
    return page.replace("<!--@GRAPHS-->", "\n".join(views)).replace("/*@DATA*/{}", data)


def as_document(page: str) -> str:
    """The page as a full HTML document, for the repository. The artifact host adds this skeleton itself."""
    head, sep, body = page.partition("<!--@HEAD-END-->")
    if not sep:
        raise SystemExit("page.html has no <!--@HEAD-END--> mark")
    head = head.replace('<meta name="claude:viewport-fit-sides" content="cover">\n', "")
    return ('<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n'
            '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n'
            "<!-- Made by scripts/blueprint/blueprint.py from docs/code-harness/blueprint.toml."
            " Do not edit by hand. -->\n"
            f"{head.strip()}\n</head>\n<body>\n{body.strip()}\n</body>\n</html>\n")


def build() -> Plan:
    meta, phases, groups, nodes = load(SOURCE)
    module_of = {g.id: g.module for g in groups}
    codes = {m: read_code(m) for m in sorted(set(module_of.values()))}
    names = {n.name for n in nodes}
    for n in nodes:
        scan(n, codes[module_of[n.group]] if n.group else None, names)
    derive(nodes)
    check_code(nodes, groups)
    return Plan(meta, phases, groups, nodes, derive_phases(phases, nodes), partial_uses(phases, nodes),
                {p.id: outside(p, phases, nodes) for p in phases})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--artifact", type=Path, help="also write the page in the form of the artifact host")
    parser.add_argument("--strict", action="store_true", help="stop with an error when the check of the code warns")
    args = parser.parse_args()
    plan = build()
    page = render(plan)
    OUTPUT.write_text(as_document(page))
    if args.artifact:
        args.artifact.write_text(page.replace("<!--@HEAD-END-->", ""))
    counts: dict[str, int] = {}
    for n in plan.nodes:
        counts[n.statement] = counts.get(n.statement, 0) + 1
    notes = [(n, note) for n in plan.nodes for note in n.notes]
    print(f"wrote {OUTPUT.relative_to(ROOT)}: {len(plan.nodes)} nodes in {len(plan.groups)} groups, "
          f"statements {counts}, {len(notes)} warnings from the check of the code")
    for n, note in notes:
        print(f"- {n.id}: {note}")
    if args.strict and notes:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
