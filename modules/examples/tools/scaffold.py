#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""
Generate Scala scaffold stubs that mirror the upstream Python DSPy docs.

For every Markdown file in the requested doc subdirs, emits one .scala file under
the dspy4s examples module. Each stub:

  - lives at a path that mirrors the source .md path
  - declares a package matching that path
  - links back to the source file (path + GitHub URL) in its header comment
  - embeds every python fenced code block as a // comment with line numbers
  - leaves a `// TODO translate snippet N` marker per block

Existing files are NEVER overwritten — once a stub is translated by hand, this
script will skip it on re-runs.
"""

import argparse
import re
import sys
from pathlib import Path

FENCE_RE = re.compile(r"^```(\w+)?\s*$")
HEADING_RE = re.compile(r"^#\s+(.+)$")


def to_pascal(name: str) -> str:
    parts = re.split(r"[-_]+", name)
    out = "".join(p[:1].upper() + p[1:] for p in parts if p)
    if not out:
        return "Doc"
    if out[0].isdigit():
        digits = ""
        rest = out
        while rest and rest[0].isdigit():
            digits += rest[0]
            rest = rest[1:]
        out = (rest or "Doc") + digits
    return out


def to_pkg_segment(name: str) -> str:
    return re.sub(r"[-]+", "_", name)


def extract_fences(text: str):
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        m = FENCE_RE.match(lines[i])
        if not m:
            i += 1
            continue
        lang = (m.group(1) or "").lower()
        start = i + 1  # 1-indexed: line of opening fence
        i += 1
        body = []
        while i < len(lines) and not FENCE_RE.match(lines[i]):
            body.append(lines[i])
            i += 1
        end = i + 1  # line of closing fence
        yield lang, "\n".join(body), start, end
        i += 1


def first_h1(text: str) -> str | None:
    for line in text.splitlines():
        m = HEADING_RE.match(line)
        if m:
            return m.group(1).strip()
    return None


def generate_stub(md_path: Path, docs_root: Path, out_root: Path, github_base: str):
    rel = md_path.relative_to(docs_root)
    parts = list(rel.parts)
    file_part = parts[-1]
    dirs = parts[:-1]
    stem = Path(file_part).stem

    if stem == "index":
        class_name = to_pascal(dirs[-1]) if dirs else "Index"
    else:
        class_name = to_pascal(stem)

    pkg_dirs = [to_pkg_segment(d) for d in dirs]
    out_dir = out_root.joinpath(*pkg_dirs) if pkg_dirs else out_root
    out_path = out_dir / f"{class_name}.scala"

    if out_path.exists():
        return ("skipped", out_path, None)

    text = md_path.read_text(errors="replace")
    title = first_h1(text) or class_name
    snippets = [s for s in extract_fences(text) if s[0] in ("python", "py")]
    src_url = f"{github_base}/{rel.as_posix()}"

    pkg_full = "dspy4s.examples" + ("." + ".".join(pkg_dirs) if pkg_dirs else "")

    lines: list[str] = []
    lines.append("/**")
    lines.append(f" * {title}")
    lines.append(" *")
    lines.append(f" * Source:   docs/docs/{rel.as_posix()}")
    lines.append(f" * Upstream: {src_url}")
    plural = "s" if len(snippets) != 1 else ""
    lines.append(
        f" * Status:   scaffold ({len(snippets)} python snippet{plural} — TODO translate)"
    )
    lines.append(" */")
    lines.append(f"package {pkg_full}")
    lines.append("")
    lines.append(f"object {class_name} {{")
    if not snippets:
        lines.append("  // No python snippets found in the source document.")
    else:
        for idx, (_, body, start, end) in enumerate(snippets, 1):
            lines.append("")
            lines.append(f"  // ── Snippet {idx} (lines {start}–{end}) " + "─" * 20)
            for py_line in body.splitlines():
                lines.append(f"  // | {py_line}".rstrip())
            lines.append(f"  // TODO translate snippet {idx}")
    lines.append("}")
    lines.append("")

    out_dir.mkdir(parents=True, exist_ok=True)
    out_path.write_text("\n".join(lines))
    return ("created", out_path, len(snippets))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--docs-root", required=True,
                    help="path to the upstream dspy/docs/docs directory")
    ap.add_argument("--out-root", required=True,
                    help="path to modules/examples/src/main/scala/dspy4s/examples")
    ap.add_argument("--include", action="append", required=True,
                    help="relative file or subdir under docs-root to include (repeatable)")
    ap.add_argument("--github-base",
                    default="https://github.com/stanfordnlp/dspy/blob/main/docs/docs",
                    help="base GitHub URL for source links")
    args = ap.parse_args(argv)

    docs_root = Path(args.docs_root).resolve()
    out_root = Path(args.out_root).resolve()

    md_files: list[Path] = []
    for inc in args.include:
        p = docs_root / inc
        if p.is_file() and p.suffix == ".md":
            md_files.append(p)
        elif p.is_dir():
            md_files.extend(sorted(p.rglob("*.md")))
        else:
            print(f"  ! not found: {inc}", file=sys.stderr)

    created = 0
    skipped = 0
    total_snippets = 0
    for md in sorted(set(md_files)):
        status, path, n = generate_stub(md, docs_root, out_root, args.github_base)
        rel_out = path.relative_to(out_root.parent.parent.parent.parent)
        if status == "created":
            created += 1
            total_snippets += n or 0
            print(f"  + {rel_out}  ({n} snippet{'s' if n != 1 else ''})")
        else:
            skipped += 1
            print(f"  · {rel_out}  (skipped: already exists)")

    print()
    print(f"created {created}, skipped {skipped}, "
          f"extracted {total_snippets} python snippet(s) total")


if __name__ == "__main__":
    main()
