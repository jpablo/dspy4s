# dspy4s Examples

This module ports the code from the [Python DSPy documentation](https://dspy.ai) to **dspy4s**, one Scala
file per doc page. Each file mirrors a page under `docs/docs/...` in the upstream repo, keeps the original
Python inline (in `// |` comment blocks) next to the Scala translation, and carries a `Status:` header
explaining what was translated and what (if anything) is out of scope.

These examples double as living documentation of the dspy4s surface and as a smoke test that the API actually
works end-to-end.

## Conventions

- **One file per doc page.** The file's header lists the upstream `Source:` / `Upstream:` page.
- **Python is preserved inline.** Each snippet shows the original Python (`// | ...`) above its Scala port.
- **`Status:` header.** Every file states `translated` / `complete` / `blocked` plus the specifics.
- **Runnable examples expose a uniquely-named top-level `@main`** (e.g. `emailExtractionMain`) so files that
  share a package don't collide on a single entry point.

## Running an example

```bash
sbt "examples/runMain <fully.qualified.mainName>"
```

LM-backed examples obtain their model through `Demo.withLm`, which reads **`OPENAI_API_KEY`** (and optional
**`DSPY_MODEL`**, default `gpt-5.5`) from the environment:

```bash
OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.email_extraction.emailExtractionMain"
```

Examples marked _offline_ below make no LM calls and need no key.

## Legend

| Mark | Meaning |
|------|---------|
| ✅ | **Ported** — runnable; the DSPy-relevant snippets are translated. |
| 🔧 | **Demo** — hand-written demonstration of a dspy4s surface (not a 1:1 doc port). |
| 📄 | **Overview** — the source doc is prose only (no code snippets); the file is a placeholder. |
| 🚫 | **Blocked** — not ported; depends on a dspy4s feature that doesn't exist yet. |
| 🔑 | Runnable example that makes **live LM calls** (needs `OPENAI_API_KEY`). |

---

## ✅ Ported examples

### `learn/programming`

| Example | Run (`examples/runMain …`) | Coverage / notes |
|---|---|---|
| **Signatures** | `…learn.programming.main` 🔑 | 8/8 snippets. String signatures → `Signature.fromType`/`fromString`; class signatures → `Spec` traits. |
| **LanguageModels** | `…learn.programming.languageModelsMain` 🔑 | LM setup/config, direct `call`, multiple LMs (`configure`/scoped override), generation config, usage, error handling. LiteLLM-only providers, Responses API, custom-LM save/load out of scope. |
| **Modules** | `…learn.programming.modulesMain` 🔑 | Portable snippets; multi-completion `n=5` access noted unsupported. |
| **Tools** | `…learn.programming.toolsMain` 🔑 | ReAct + `ToolFunction.fromMethod`. Manual tool-call path / native function-calling / async tools out of scope. |
| **Adapters** | `…learn.programming.adaptersMain` 🔑 | 1–6: Predict, `adapter.format(...)`, ChatAdapter/JSONAdapter selection. `inspect_history` dropped. |

### `learn/evaluation`

| Example | Run | Coverage / notes |
|---|---|---|
| **Data** | `…learn.evaluation.dataMain` _(offline)_ | `Example` construction + inputs/labels. `DataLoader` (csv/json/parquet/HF) not ported. |
| **Metrics** | `…learn.evaluation.metricsMain` _(offline)_ | `FunctionMetric` + `Evaluate`. LLM-as-judge / retrieval-trace metrics not supported (`Metric.score` has no `RuntimeContext`). |

### `learn/optimization`

| Example | Run | Coverage / notes |
|---|---|---|
| **Optimizers** | `…learn.optimization.optimizersMain` 🔑 | `BootstrapFewShotWithRandomSearch.compile`. Program `.save`/`.load` not ported. |

### `deep_dive/data_handling`

| Example | Run | Coverage / notes |
|---|---|---|
| **Examples** | `…deep_dive.data_handling.examplesMain` _(offline)_ | Full `dspy.Example` API (1–6). Immutable `case class`, so `withValue(...)` replaces in-place mutation. |
| **LoadingCustomData** | `…deep_dive.data_handling.loadingCustomDataMain` _(offline)_ | Build `Example`s from rows + train/dev split. CSV/pandas I/O and `Dataset` base class out of scope. |

### `tutorials`

| Example | Run (`…tutorials.<pkg>.<main>`) | Coverage / notes |
|---|---|---|
| **email_extraction** | `email_extraction.emailExtractionMain` 🔑 | Enums/case classes/`Option`/`List` via `derives Schema`; four composed `ChainOfThought`s. MLflow autolog out of scope. |
| **output_refinement** | `output_refinement.bestOfNAndRefineMain` 🔑 | 6/6. Typed `BestOfN[I,O]` / `Refine[I,O]` with typed reward functions. |
| **streaming** | `streaming.streamingMain` 🔑 | 1–9. Synchronous `streamify` → `ClosableIterator[StreamEvent]`; per-field & per-predict listeners; status provider. |
| **async** | `async.asyncMain` 🔑 | `acall` → `applyAsync`/`ContextPropagation.future`. Async _tools_ not portable (synchronous `ToolFunction`). |
| **cache** | `cache.cacheMain` 🔑 | Per-LM `ManagedLanguageModel(cache=…)` (Noop/InMemory/Disk/custom) + usage tracking. No global `configure_cache`. |
| **observability** | `observability.observabilityMain` 🔑 | ReAct + custom `CallbackHandler` (`on_module_end` → `ModuleEndEvent`). `inspect_history`/MLflow/ColBERT out of scope. |
| **sample_code_generation** | `sample_code_generation.sampleCodeGenerationMain` 🔑 | Two signatures + refine CoT + composing agent. URL fetching / interactive console / JSON save out of scope. |
| **ai_text_game** | `ai_text_game.aiTextGameMain` 🔑 | Three signatures + `GameAI` (`dict[str,int]`→`Map`). Save/load, console UI, game loop out of scope. |
| **yahoo_finance_react** | `yahoo_finance_react.yahooFinanceReactMain` 🔑 | ReAct + finance tools via `ToolFunction.fromMethod`. Live yfinance/LangChain data stubbed. |
| **llms_txt_generation** | `llms_txt_generation.llmsTxtMain` 🔑 | Signatures + composed pipeline. GitHub HTTP fetching out of scope. |

### Top-level

| Example | Run | Coverage / notes |
|---|---|---|
| **Cheatsheet** | `…examples.cheatsheetMain` _(offline)_ | Portable cheatsheet snippets (modules, metrics, optimizers, tools, streaming, cache, refinement). Retrieval / save-load / unported optimizers (MIPROv2, COPRO, SIMBA, …) marked inline. |

> `Demo.scala` is not an example — it's the shared runner (`Demo.withLm`) that wires an OpenAI LM + `ChatAdapter`
> into the ambient `RuntimeContext` for the 🔑 examples.

---

## 🔧 Typed-signature demos (`typed/`)

Hand-written demonstrations of the four ways to declare a typed `Signature` (not 1:1 doc ports).

| Example | Run | Surface |
|---|---|---|
| **BuilderExample** | `…typed.builderMain` _(offline)_ | Programmatic `Signature.builder(...)`. |
| **CaseClassExample** | `…typed.caseClassMain` 🔑 | `Signature.derived[In, Out]` from two case classes. |
| **FunctionExample** | `…typed.functionMain` 🔑 | `Signature.fromType[(in: I) => (out: O)]`. |
| **SpecExample** | `…typed.specMain` 🔑 | `Signature.of[T <: Spec]` from a trait with `InputField`/`OutputField`. |

---

## 📄 Section docs (adapted prose pages)

The upstream DSPy pages that are prose only (overviews, landing/index pages, links to external tutorials)
have no code to port. Instead of empty Scala placeholders, each is a `README.md` adapted for dspy4s — same
topic, rewritten for the Scala port, with attribution to the MIT-licensed source and links to the relevant
example files:

- [`learn/`](./src/main/scala/dspy4s/examples/learn/README.md) — learning dspy4s overview (the three stages)
- [`learn/programming/`](./src/main/scala/dspy4s/examples/learn/programming/README.md) — programming in dspy4s
- [`learn/evaluation/`](./src/main/scala/dspy4s/examples/learn/evaluation/README.md) — evaluation in dspy4s
- [`learn/optimization/`](./src/main/scala/dspy4s/examples/learn/optimization/README.md) — optimization in dspy4s
- [`production/`](./src/main/scala/dspy4s/examples/production/README.md) — dspy4s in production
- [`tutorials/`](./src/main/scala/dspy4s/examples/tutorials/README.md) — tutorials index (ported / blocked / external)
- tutorial landing pages: `build_ai_program`, `core_development`, `real_world_examples`,
  `optimize_ai_program`, `gepa_ai_program`, `rl_ai_program`, `classification`, `papillon`
  (each a `README.md` under `tutorials/<name>/`).

---

## 🚫 Blocked — not ported

These depend on dspy4s features that don't exist yet. Each file's header points at the closest supported
alternative where one exists.

| Example | Blocked on | Supported alternative |
|---|---|---|
| `deep_dive/data_handling/BuiltInDatasets` | built-in dataset loaders (`dspy.datasets`, HotPotQA/GSM8K) | `LoadingCustomData` (build `Example`s yourself) |
| `learn/programming/Assertions7` | `dspy.Assert` / `dspy.Suggest` backtracking | `output_refinement` (`Refine` / `BestOfN`) |
| `learn/programming/Mcp` & `tutorials/mcp` | MCP client/tool bridge | plain `ToolFunction`s (`learn/programming/Tools`) |
| `tutorials/conversation_history` | `dspy.History` input, `inspect_history`, mutable `predict.demos` | — |
| `tutorials/deployment` | model serving (FastAPI app / MLflow serving) | wrap a program in your own HTTP layer |
| `tutorials/mem0_react_agent` | mem0 long-term memory store | ReAct itself is portable (`Tools`) |
| `tutorials/optimizer_tracking` | MLflow autolog, MIPROv2, datasets | — |
| `tutorials/saving` | program `.save`/`.load`, GSM8K dataset | — |

---

## At a glance

- **21** ported doc examples (✅) + **4** typed-surface demos (🔧) = **25** runnable `@main`s.
- **6** run offline (no key): `dataMain`, `metricsMain`, `builderMain`, `examplesMain`, `cheatsheetMain`,
  `loadingCustomDataMain`. The other 19 need `OPENAI_API_KEY` (🔑).
- **14** adapted prose docs (📄, now `README.md` per section) and **9** blocked files (🚫) — shown as 8 rows above (the two MCP pages share one).
- **35** Scala source files (25 runnable + 9 blocked + the shared `Demo` runner) plus **14** section `README.md`s.

When a feature lands in dspy4s (datasets, MCP, save/load, …), the corresponding 🚫 file is the next thing to
port — its header already records exactly what's missing.
