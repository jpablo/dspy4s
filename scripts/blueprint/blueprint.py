"""Makes the blueprint page of the code harness: the plan as a graph, with the state of each node.

Usage:
    python3 scripts/blueprint/blueprint.py [--artifact PATH]

The script reads docs/code-harness/blueprint.toml, the Scala sources and the test reports of each phase, and writes
docs/code-harness/CODE_HARNESS_BLUEPRINT.html. It needs Graphviz (`dot`) and Python 3.12. With --artifact, it also
writes the page in the form that the artifact host takes, with no doctype, html, head or body.

Each node has two states, as in a Lean blueprint:
- The statement (the border): a type or function is stated when its declaration is in the code. A law is stated when
  a test with its name is in the code. A node is ready when each node that it uses is stated.
- The proof (the fill): a type is done when it and its methods have bodies, a function when it has a body that is not
  `???`, and a law when its tests pass in the last test report. A node is ready when it is stated and each node that
  it uses is done. A node is complete when it and all the nodes under it are done.
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


@dataclass
class Phase:
    id: str
    number: int
    title: str
    modules: list[str]
    uses: list[str]
    result: str
    sources: str | None = None
    tests: str | None = None
    reports: str | None = None


@dataclass
class Node:
    id: str
    phase: str
    kind: str
    name: str
    ref: str
    uses: list[str]
    text: str = ""
    ops: list[str] = field(default_factory=list)
    where: str = "main"
    spec: bool = False
    reviewed: str | None = None
    blocked: str | None = None
    # Found by the scan:
    stated: bool = False
    done: bool = False
    statement: str = ""
    proof: str = ""
    why_statement: str = ""
    why_proof: str = ""

    @property
    def title(self) -> str:
        return self.name.rstrip(":") if self.kind in ("law", "check") else self.name


@dataclass
class Code:
    main: str
    tests: str
    results: dict[str, bool]


# ── The source file ──────────────────────────────────────────────────────────────────────────────────────────────


def load(path: Path) -> tuple[dict, list[Phase], list[Node]]:
    data = tomllib.loads(path.read_text())
    phases = [Phase(**p) for p in data["phase"]]
    nodes = [Node(**n) for n in data["node"]]
    check(phases, nodes)
    return data["meta"], phases, nodes


def check(phases: list[Phase], nodes: list[Node]) -> None:
    """Stops with a clear message when the source file has a mistake."""
    problems: list[str] = []
    phase_ids = [p.id for p in phases]
    node_ids = [n.id for n in nodes]
    for kind, ids in (("phase", phase_ids), ("node", node_ids)):
        twice = sorted({i for i in ids if ids.count(i) > 1})
        if twice:
            problems.append(f"each {kind} id must be unique: {', '.join(twice)}")
    for p in phases:
        problems += [f"phase {p.id} uses the unknown phase {u}" for u in p.uses if u not in phase_ids]
    for n in nodes:
        if n.kind not in KINDS:
            problems.append(f"node {n.id} has the unknown kind {n.kind}")
        if n.phase not in phase_ids:
            problems.append(f"node {n.id} has the unknown phase {n.phase}")
        problems += [f"node {n.id} uses the unknown node {u}" for u in n.uses if u not in node_ids]
        if n.kind in ("law", "check") and not n.name.endswith(":"):
            problems.append(f"the name of the {n.kind} {n.id} must end with a colon, for example \"Fold:\"")
    by_id = {n.id: n for n in nodes}
    for start in nodes:
        if cycle := find_cycle(start.id, {k: v.uses for k, v in by_id.items()}):
            problems.append("the uses make a cycle: " + " -> ".join(cycle))
            break
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


# ── The scan of the code and the test reports ────────────────────────────────────────────────────────────────────


def read_code(phase: Phase) -> Code:
    def text(rel: str | None) -> str:
        if rel is None or not (ROOT / rel).is_dir():
            return ""
        return "\n".join(f.read_text() for f in sorted((ROOT / rel).rglob("*.scala")))

    results: dict[str, bool] = {}
    if phase.reports and (ROOT / phase.reports).is_dir():
        for report in sorted((ROOT / phase.reports).glob("*.xml")):
            for case in ET.parse(report).getroot().iter("testcase"):
                bad = any(child.tag in ("failure", "error", "skipped") for child in case)
                name = case.get("name", "")
                results[name] = results.get(name, True) and not bad
    return Code(text(phase.sources), text(phase.tests), results)


def declared_type(text: str, name: str) -> re.Match[str] | None:
    return re.search(rf"^([ \t]*)(?:[a-z ]*\s)?(?:enum|class|trait|object|type)\s+{re.escape(name)}\b", text, re.M)


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


def find_def(text: str, name: str) -> list[int]:
    return [m.start() for m in re.finditer(rf"\bdef\s+{re.escape(name)}\b", text)]


def block_of(text: str, name: str) -> str:
    """The text of each declaration of the type `name` and of its companion object."""
    parts = []
    for m in re.finditer(rf"^([ \t]*)(?:[a-z ]*\s)?(?:enum|class|trait|object)\s+{re.escape(name)}\b", text, re.M):
        indent = len(m.group(1))
        body = text.find("\n", m.end()) + 1 or len(text)  # the block ends on a later line, not on this one
        end = len(text)
        for line in re.finditer(r"^( *)([^\s)\]}])", text[body:], re.M):  # a closing bracket does not end it
            if len(line.group(1)) <= indent:
                end = body + line.start()
                break
        parts.append(text[m.start():end])
    return "\n".join(parts)


def scan(node: Node, code: Code) -> None:
    if node.kind == "upstream":
        node.stated = node.done = True
    elif node.kind == "data":
        node.stated = declared_type(code.main, node.name) is not None
        block = block_of(code.main, node.name)
        node.done = node.stated and all(any(has_body(block, p) for p in find_def(block, op)) for op in node.ops)
    elif node.kind == "function":
        text = code.tests if node.where == "tests" else code.main
        starts = find_def(text, node.name)
        node.stated = bool(starts)
        node.done = any(has_body(text, p) for p in starts)
    else:  # a law or a check: the tests are the statement
        node.stated = f'"{node.name}' in code.tests
        runs = [ok for name, ok in code.results.items() if name.startswith(node.name)]
        node.done = node.stated and bool(runs) and all(runs)


# ── The states ───────────────────────────────────────────────────────────────────────────────────────────────────


def names(ids: list[str], by_id: dict[str, Node]) -> str:
    return ", ".join(by_id[i].title for i in ids)


def derive(nodes: list[Node]) -> None:
    by_id = {n.id: n for n in nodes}
    order = topological(nodes)
    for n in order:
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

        not_done = [d.id for d in deps if d.proof not in ("done", "complete")]
        if n.kind == "upstream":
            n.proof, n.why_proof = "complete", "It comes from a library."
        elif n.done:
            all_complete = all(d.proof == "complete" for d in deps)
            n.proof = "complete" if all_complete else "done"
            what = {"data": "The type and its methods have bodies.", "function": "The function has a body."}
            under = (" It and all the nodes under it are done." if all_complete
                     else " Some nodes under it are not complete.")
            n.why_proof = what.get(n.kind, "Its tests pass.") + under
        elif n.stated and not not_done:
            n.proof, n.why_proof = "ready", "Each node that it uses is done, so the work can start."
        else:
            n.proof = "none"
            n.why_proof = (
                "The statement comes first." if not n.stated
                else f"These nodes are not done yet: {names(not_done, by_id)}."
            )


def topological(nodes: list[Node]) -> list[Node]:
    by_id = {n.id: n for n in nodes}
    seen: set[str] = set()
    out: list[Node] = []
    def visit(n: Node) -> None:
        if n.id in seen:
            return
        seen.add(n.id)
        for u in n.uses:
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
        own = [n for n in nodes if n.phase == p.id and n.kind != "upstream"]
        s = PhaseState(
            total=len(own),
            stated=sum(n.stated for n in own),
            done=sum(n.done for n in own),
            spec=sum(n.spec for n in own),
            reviewed=sum(bool(n.reviewed) for n in own if n.spec),
        )
        deps_complete = all(states[u].proof == "complete" for u in p.uses)
        if own and s.done == s.total:
            s.statement, s.proof = "stated", "complete" if deps_complete else "done"
            s.why = "Each node of the phase is done."
        elif own and s.stated == s.total:
            s.statement, s.proof = "stated", "ready" if deps_complete else "none"
            s.why = "Each node of the phase is stated."
        elif deps_complete:
            s.statement = "ready"
            s.why = ("The phases that it uses are complete, so the work can start." if p.uses
                     else "It uses no other phase, so the work can start.")
            if not own:
                s.why += " The phase has no nodes in the blueprint yet."
        else:
            s.statement = "waiting"
            s.why = "These phases are not complete yet: " + ", ".join(
                f"phase {by_id[u].number}" for u in p.uses if states[u].proof != "complete") + "."
        states[p.id] = s
    return states


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


def node_svg(nodes: list[Node]) -> str:
    lines = header("phase")
    for n in nodes:
        mark = " · spec ✓" if n.spec and n.reviewed else " · spec" if n.spec else ""
        sub = f"§{n.ref}{mark}" + (" · tests" if n.where == "tests" else "")
        label = (f'<<FONT POINT-SIZE="11"><B>{esc(n.title)}</B></FONT><BR/>'
                 f'<FONT POINT-SIZE="8">{esc(sub)}</FONT>>')
        classes = f"kind-{n.kind} st-{n.statement} pf-{n.proof}" + (" spec" if n.spec else "")
        extra = ", peripheries=2" if n.spec else ""
        lines.append(f'  "{n.id}" [id="n-{n.id}", class="{classes}", label={label}, {SHAPES[n.kind]}{extra}];')
    ids = {n.id for n in nodes}
    for user, dep in reduced({n.id: [u for u in n.uses if u in ids] for n in nodes}):
        lines.append(f'  "{dep}" -> "{user}" [id="e-{dep}--{user}"];')
    lines.append("}")
    return run_dot(lines)


def plan_svg(phases: list[Phase], states: dict[str, PhaseState]) -> str:
    lines = header("plan")
    for p in phases:
        s = states[p.id]
        modules = ", ".join(p.modules) if p.modules else "in the modules above"
        count = f"{s.stated} of {s.total} stated · {s.done} done" if s.total else "not in the blueprint yet"
        label = (f'<<FONT POINT-SIZE="12"><B>{p.number} · {esc(p.title)}</B></FONT><BR/>'
                 f'<FONT POINT-SIZE="9">{esc(modules)}</FONT><BR/><FONT POINT-SIZE="9">{esc(count)}</FONT>>')
        classes = f"kind-phase st-{s.statement} pf-{s.proof}"
        lines.append(f'  "{p.id}" [id="n-{p.id}", class="{classes}", label={label}, {SHAPES["phase"]}];')
    for p in phases:
        for u in p.uses:
            lines.append(f'  "{u}" -> "{p.id}" [id="e-{u}--{p.id}"];')
    lines.append("}")
    return run_dot(lines)


# ── The page ─────────────────────────────────────────────────────────────────────────────────────────────────────


def git(*args: str) -> str:
    try:
        done = subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True, check=True)
        return done.stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return ""


def page_data(meta: dict, phases: list[Phase], nodes: list[Node], states: dict[str, PhaseState]) -> dict:
    users: dict[str, list[str]] = {n.id: [] for n in nodes}
    for n in nodes:
        for u in n.uses:
            users[u].append(n.id)
    by_phase = {p.id: [n for n in nodes if n.phase == p.id] for p in phases}
    return {
        "title": meta["title"],
        "commit": git("rev-parse", "--short", "HEAD"),
        "date": git("log", "-1", "--format=%cs"),
        "views": [{"id": "plan", "tab": "The plan"}] + [
            {"id": f"phase-{p.number}", "tab": f"Phase {p.number}: {p.title}", "phase": p.id}
            for p in phases if by_phase[p.id]
        ],
        "phases": {
            f"n-{p.id}": {
                "title": f"Phase {p.number}: {p.title}", "kind": "phase", "modules": p.modules,
                "result": p.result, "uses": [f"n-{u}" for u in p.uses],
                "users": [f"n-{q.id}" for q in phases if p.id in q.uses],
                "view": f"phase-{p.number}" if by_phase[p.id] else None, **vars(states[p.id]),
            }
            for p in phases
        },
        "nodes": {
            f"n-{n.id}": {
                "title": n.title, "kind": n.kind, "phase": n.phase, "ref": n.ref, "text": n.text,
                "spec": n.spec, "reviewed": n.reviewed, "blocked": n.blocked, "where": n.where, "ops": n.ops,
                "statement": n.statement, "proof": n.proof, "whyStatement": n.why_statement, "whyProof": n.why_proof,
                "uses": [f"n-{u}" for u in n.uses], "users": [f"n-{u}" for u in users[n.id]],
            }
            for n in nodes
        },
    }


def render(meta: dict, phases: list[Phase], nodes: list[Node], states: dict[str, PhaseState]) -> str:
    page = TEMPLATE.read_text()
    views = [f'<div class="view-graph" id="graph-plan" data-view="plan">{plan_svg(phases, states)}</div>']
    for p in phases:
        own = [n for n in nodes if n.phase == p.id]
        if own:
            views.append(f'<div class="view-graph" id="graph-phase-{p.number}" data-view="phase-{p.number}" hidden>'
                         f"{node_svg(own)}</div>")
    data = json.dumps(page_data(meta, phases, nodes, states), ensure_ascii=False, indent=None).replace("</", "<\\/")
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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--artifact", type=Path, help="also write the page in the form of the artifact host")
    args = parser.parse_args()
    meta, phases, nodes = load(SOURCE)
    for p in phases:
        code = read_code(p)
        for n in nodes:
            if n.phase == p.id:
                scan(n, code)
    derive(nodes)
    states = derive_phases(phases, nodes)
    page = render(meta, phases, nodes, states)
    OUTPUT.write_text(as_document(page))
    if args.artifact:
        args.artifact.write_text(page.replace("<!--@HEAD-END-->", ""))
    counts: dict[str, int] = {}
    for n in nodes:
        counts[n.statement] = counts.get(n.statement, 0) + 1
    print(f"wrote {OUTPUT.relative_to(ROOT)}: {len(nodes)} nodes, statements {counts}")


if __name__ == "__main__":
    main()
