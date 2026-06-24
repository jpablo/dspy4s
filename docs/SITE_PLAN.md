# dspy4s Documentation Site — Plan

A landing page + self-contained documentation site for dspy4s, in the spirit of
[dspy.ai](https://dspy.ai/), built on **compiler-verified code snippets**.

## Decisions (locked)

| Decision | Choice | Rationale |
|---|---|---|
| Static site generator | **MkDocs Material** | The exact stack dspy.ai uses — closest look for least theming. Adds only Python/`uv`, which the repo already uses for `tools/scaffold.py`. |
| Snippet mechanism | **Region extraction** from `modules/examples` via `pymdownx.snippets` | Single source of truth; no copy-paste; snippets are already strict-compiled. |
| Landing page | **Minimal hero, docs-first** | Simple hero now (tagline + install + "Get Started"); upgradeable to a custom `home.html` later without rework. |
| Hosting | **GitHub Pages** via Actions | Static `site/` output, no runtime deps. |

## Key premise: the hard part already exists

The expensive part of a docs site like dspy.ai is a corpus of *correct, current* code
examples. `modules/examples` already is one:

- **Mirrors dspy.ai's IA 1:1** — `learn/programming`, `learn/evaluation`,
  `learn/optimization`, `deep_dive`, `tutorials`, `production`.
- **Already "compiler-verified"** — it builds under the strict flags in `build.sbt`
  (`-Werror`, `-Wunused:all`, `-Wnonunit-statement`, `-language:strictEquality`).
  A wrong snippet fails `sbt examples/compile`.
- **Carries the Python original inline** (`// |` blocks) next to each Scala port, with
  `Source:` / `Upstream:` / `Status:` headers and a coverage index in
  `modules/examples/README.md`.
- **Already has a Python/`uv` toolchain** (`tools/scaffold.py`, run via `uv`).

So the design centers on getting snippets from the compiled module onto the page
**without copy-paste** (which would go stale).

## Architecture: single source of truth → region extraction → CI gate

```
modules/examples/**.scala   ←  the ONLY place snippet code lives (IDE-supported, strict-compiled)
        │   labeled regions:  // --8<-- [start:toxicity] … // --8<-- [end:toxicity]
        ▼
   MkDocs (pymdownx.snippets) pulls regions by label into pages (no duplication)
        ▼
   CI:  sbt examples/compile   MUST pass before the site builds/deploys
        ▼
   static HTML  →  GitHub Pages
```

Every snippet on the live site is, by construction, code that compiles under the strict
flags.

### Why not mdoc?

mdoc is the usual Scala answer, but for *this* repo region-extraction is simpler and
stronger: mdoc wants snippet code embedded in markdown, which would duplicate the curated
`.scala` files, lose IDE/refactor support, and — since most dspy4s snippets are LM-backed
(API keys, nondeterministic) — mdoc's "run and capture output" can't run at build time
anyway. Extracting regions from the already-compiled module gives the same guarantee
without the duplication.

## Layout

```
site/
  mkdocs.yml                # single config file
  pyproject / inline uv     # mkdocs-material + pymdownx, run via uv (like scaffold.py)
  docs/
    index.md                # minimal hero (homepage)
    get-started/ …
    learn/programming/ …    # nav mirrors modules/examples/…
    learn/evaluation/ …
    learn/optimization/ …
    tutorials/ …
    cheatsheet.md
    stylesheets/extra.css   # small brand tweaks
    overrides/             # custom templates only if/when the hero needs them
.github/workflows/docs.yml  # sbt examples/compile → mkdocs build → Pages
```

The repo root is `base_path` for snippets so `--8<--` can reach into `modules/examples/...`.

## Snippet pipeline — worked example

Region markers are plain Scala line comments (compiler- and scalafmt-safe). In
`Signatures.scala`:

```scala
// --8<-- [start:toxicity]
object ToxicityExample:
  val signature =
    Signature.fromType[(comment: String) => (toxic: Boolean)](
      instructions = "Mark as 'toxic' if the comment includes insults…"
    )
  val toxicity = Predict(signature)
// --8<-- [end:toxicity]
```

The doc page pulls it by label — never copy-pastes it:

````markdown
A signature declares typed inputs and outputs:

```scala
--8<-- "modules/examples/src/main/scala/dspy4s/examples/learn/programming/Signatures.scala:toxicity"
```
````

`mkdocs.yml` enables it:

```yaml
markdown_extensions:
  - pymdownx.snippets:
      base_path: [..]          # repo root
      check_paths: true        # build FAILS if a referenced file/region is missing
  - pymdownx.superfences
  - pymdownx.tabbed: { alternate_style: true }   # Python-vs-Scala / multi-step code tabs
```

`check_paths: true` means a renamed file or deleted region breaks the build, so the docs
cannot silently drift from the code.

## Three independent drift guarantees

1. `sbt examples/compile` — every snippet compiles under strict flags.
2. `pymdownx.snippets check_paths: true` — every referenced file/region exists.
3. `mkdocs build --strict` — no broken internal links or missing snippets.

## Information architecture (taken from the examples tree)

```
Home             minimal hero (tagline · install · Get Started · one code showcase)
Get Started      install · quickstart · why-Scala
Learn
  Programming    Signatures · Modules · LMs · Adapters · Tools
  Evaluation     Data · Metrics
  Optimization   Optimizers (COPRO / MIPRO / GEPA)
Deep Dive        Data Handling
Tutorials        ~24 pages (email extraction, ReAct, streaming, RL, …)
Cheatsheet
API              link out to generated Scaladoc (separate)
```

The `nav:` block maps 1:1 to `modules/examples/...`; the README coverage table
(✅ ported / 🚫 blocked) drives porting order.

## CI / deploy (`.github/workflows/docs.yml`)

```
1. sbt examples/compile          # strict -Werror gate; fails on any broken snippet
2. uv run mkdocs build --strict  # fails on broken links / missing snippets
3. deploy site/ to GitHub Pages  # on main only
```

`mkdocs build` emits a fully static `site/` folder with no runtime dependency.

## Phases

1. **Pipeline proof** — `site/` scaffold + region markers on `Signatures.scala`; one page
   rendering verified snippets locally. Proves the whole chain end-to-end.
2. **CI gate** — the Actions workflow + Pages deploy.
3. **Landing + Get Started** — minimal hero, install, quickstart.
4. **Port docs** — page by page (markers + prose), `learn/*` first, then tutorials; link a
   Scaladoc "API" tab.

## Out of scope (for now)

- Full custom `home.html` marketing hero (deferred; minimal hero first).
- Generated Scaladoc API site (linked as a separate artifact later).
- Versioned docs (`mike`) — add once there is a released version to pin.
