# dspy4s documentation site

A [MkDocs Material](https://squidfunk.github.io/mkdocs-material/) site whose code
snippets are **extracted from the compiled `modules/examples` sources**, so every
sample is guaranteed to compile. See [`docs/SITE_PLAN.md`](../docs/SITE_PLAN.md)
for the design.

## Prerequisites

[`uv`](https://docs.astral.sh/uv/) (already used by `modules/examples/tools/scaffold.py`).
It manages Python and the MkDocs dependencies declared in `pyproject.toml`, so no
manual `pip install` or virtualenv is needed.

## Develop

```bash
cd site
uv run mkdocs serve      # live-reload preview at http://127.0.0.1:8000
```

## Build

```bash
cd site
uv run mkdocs build --strict   # fails on broken links or missing snippet regions
```

Output goes to `target/site/` (gitignored).

## How snippets work

Code lives **only** in `modules/examples/**.scala`, fenced by line comments:

```scala
// --8<-- [start:toxicity]
object ToxicityExample:
  ...
// --8<-- [end:toxicity]
```

A doc page pulls a region by label. It never copies the code:

````markdown
```scala
--8<-- "learn/programming/Signatures.scala:toxicity"
```
````

`pymdownx.snippets` is configured with `check_paths: true`, so a renamed file or
deleted region **fails the build**. Combined with `sbt examples/compile` in CI,
this guarantees every rendered snippet exists and compiles.
