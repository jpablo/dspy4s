# Example Port Candidates

> Curated catalog of DSPy tutorials/examples in the wild that would make
> **high-quality showcases for dspy4s**. Compiled 2026-06 from two sources:
> all 23 issues of [DSPyWeekly](https://dspyweekly.com/archives/) (issues 1–23)
> and the [ganarajpr/awesome-dspy](https://github.com/ganarajpr/awesome-dspy)
> list. Every candidate was followed one link deep, classified, and judged for
> portability against dspy4s's actual feature surface.
>
> This is a *sourcing* doc — what's worth porting and why. For what's already
> ported, see [`../../modules/examples/`](../../modules/examples/) and the
> [real-world examples README](../../modules/examples/src/main/scala/dspy4s/examples/tutorials/real_world_examples/README.md).
> For the feature scope these judgments rest on, see [`PORT_SCOPE.md`](PORT_SCOPE.md).

## How candidates were judged

**dspy4s capability profile** (the portability lens):

- **Supported** — `Predict`, `ChainOfThought`, `ReAct` (text-protocol tool
  calling), `Parallel`, `BestOfN`, `Refine`, `MultiChainComparison`,
  `ProgramOfThought`, `CodeAct`, **`RLM`** (sandboxed Pyodide/Deno interpreter).
  Optimizers: `LabeledFewShot`, `BootstrapFewShot`,
  `BootstrapFewShotWithRandomSearch`, `KNNFewShot`, `Ensemble`, `GEPA`,
  `MIPROv2`, `COPRO`, `InferRules`. `Evaluate` + metrics. Adapters: Chat/JSON/XML.
  Typed signatures (case-class / zio-blocks Schema derivation). Embedders +
  retrievers. MCP tool calling. Streaming (minimal). LM providers:
  OpenAI-compatible + Ollama.
- **Not supported / out of scope** — mem0 memory integration, fine-tuning
  (`GRPO`/`BootstrapFinetune`/SFT), `SIMBA`, `BetterTogether`, the
  Anthropic-native provider, the Python `datasets` module, full multimodal,
  `ReActV2` native-tool-calling, and `dspy.experimental.*` (removed upstream).
  Anything leaning on Python-only libs (pandas, LangChain, RDKit, FAISS,
  sentence-transformers) needs a Scala equivalent or a precompute/stub step.

**Ranking criteria** — showcase value (does it demonstrate a *distinctive*
dspy4s capability?) × portability × self-containedness (does it ship data / run
offline?). Items that merely re-demonstrate an already-exampled feature are
deprioritized even when trivially portable.

**Methodology caveats**

- DSPyWeekly's URL slugs are *not* the issue numbers (e.g. issue 7 =
  `/newsletter/8/`, issue 23 = `/newsletter/26/`). Issue numbers below are the
  canonical archive numbers.
- YouTube **descriptions** could not be fetched (only page chrome came back), so
  code links buried in video descriptions are unconfirmed, not absent. Most
  flagged videos were talks/interviews regardless. Re-mining them needs a
  browser tool or the YouTube Data API.

## What's already covered (don't re-port)

- **Shipped examples** already cover: `BestOfN`/`Refine` (`output_refinement`),
  ReAct + tools (`yahoo_finance_react`), MCP, streaming, observability,
  optimizer-tracking, email extraction, llms.txt generation, sample-code-gen,
  the AI text game, and the `learn/` walkthroughs (signatures, modules,
  adapters, tools, optimizers, metrics).
- **Observability integrations** from awesome-dspy (Arize-Phoenix, Opik,
  Langtrace, Future AGI, OpenInference) are covered by `tutorials/observability`.
- **Already assessed during the scan / deduped**: `DSPydantic`, `xmc.dspy`,
  `dspy-arxiv`, `dspy-compounding-engineering`, `voicetest`.
- **Other-language ports** (dsprrr/R, DSPy.rb, ds_ex/Elixir, DSRs/Rust, ax/TS)
  aren't port targets, but [`jameshwade/dsprrr`](https://github.com/jameshwade/dsprrr)
  is an excellent **tutorial-design reference** — its progression maps ~1:1 onto
  dspy4s features.

---

## Tier 1 — port these first

The biggest gaps are flagship features with **zero** end-to-end showcase: `RLM`
(shipped, genuinely differentiating, completely unexampled), `GEPA` on a real
task with a feedback metric (only a smoke test exists), a dramatic optimizer
before/after, an agentic coding `ReAct` loop, a new adapter, and reasoning-
structure composition.

| # | Example | Code | Showcases | Self-contained | Notes |
|---|---------|------|-----------|----------------|-------|
| 1 | **ReAct vs RLM, head-to-head** (issue 23) | [RamXX/react2rlm](https://github.com/RamXX/react2rlm) | `RLM` **and** `ReAct` on the same compositional multi-tool task; measures tool-call coverage | Needs Groq key + Deno | ⭐ Best single pick. Already uses **Deno** (dspy4s's sandbox) + Groq (OpenAI-compatible). Pits two flagships against each other. |
| 2 | **RLM codebase analyzer** (issue 22) | [weshoke gist](https://gist.github.com/weshoke/5a0f6f632dd80d34b3c0e5dc867c0d9c) | `RLM` + typed signatures (security/docs/quality/architecture audit modes), `sub_lm` cost control | Single file; runs on any source tree | Verify `sub_lm` surface in dspy4s `RLM`. Loader + CLI are trivial Scala. |
| 3 | **RLM needle-in-haystack** (issue 8) | [codecrack3/…RLM](https://github.com/codecrack3/Recursive-Language-Models-RLM-with-DSpy) · [halfprice06/rlm_dspy](https://github.com/halfprice06/rlm_dspy) | `RLM` recursive context partitioning over 60k-token docs | Bundled/synthetic docs | The "80% vs 0%" wow factor. Sandbox replaces RestrictedPython/E2B directly. |
| 4 | **GEPA listwise reranker** (issue 3) | [weaviate/recipes notebook](https://github.com/weaviate/recipes/blob/main/integrations/llm-agent-frameworks/dspy/GEPA-Hands-On-Reranker.ipynb) | `GEPA` + `ChainOfThought` + **feedback metric** (score + NL feedback); Recall@1 32%→45% | 138 EnronQA Qs from HF → bundle as JSON | ⭐ Cleanest GEPA showcase. No live Weaviate needed. |
| 5 | **Optimizer bake-off** (issue 5) | [AADARSH96/dspy-prompt-optimizer](https://github.com/AADARSH96/dspy-prompt-optimizer) | baseline vs **MIPROv2 vs GEPA** on classification, custom metrics, save/load | Ships `dataset.json` | Perfect "which optimizer wins" demo. |
| 6 | **MIPROv2 dramatic lift** (issue 17) | [Mindfire post](https://www.mindfiretechnology.com/blog/archive/how-dspy-optimizes-prompts/) → [brucenielson/DSPyTutorials](https://github.com/brucenielson/DSPyTutorials) | `MIPROv2` takes sentiment 20%→100% | Yes, minimal | Pairs with its sibling "How DSPy *Builds* Prompts" (`inspect_history`). |
| 7 | **Coding agent (Claude-Code clone)** (issue 18) | [Fadeleke57/nanocode](https://github.com/Fadeleke57/nanocode) | `ReAct` full agentic loop with read/write/edit/glob/grep/bash tools | ~250 LOC, zero deps beyond DSPy | ⭐ Maps 1:1 to dspy4s text-protocol ReAct. Big "look what you can build" appeal. |
| 8 | **TOON adapter** (issue 15) | [Archelunch/dspy-toon](https://github.com/Archelunch/dspy-toon) | A new **adapter** (Token-Oriented Object Notation), ~40% token cut vs JSON | Yes | Extends the Chat/JSON/XML adapter family — a natural new Scala codec. |
| 9 | **NL-to-SQL, self-refining** (issue 10) | [Codecademy "What is DSPy?"](https://www.codecademy.com/article/what-is-dspy) | `ChainOfThought` SQL gen that auto-repairs on SQLite error messages | Ollama + in-memory SQLite, no external deps | GEPA-optimized variant: [lsb/dspy-sql-chat](https://github.com/lsb/dspy-sql-chat) — **AGPL**, port the technique not the code. |
| 10 | **Self-Discover (reasoning composition)** (awesome-dspy) | [Colab](https://colab.research.google.com/drive/1GkAQKmw1XQgg5UNzzy8OncRe79V6pADB) · [paper](https://arxiv.org/abs/2402.03620) | SELECT→ADAPT→IMPLEMENT→SOLVE composite reasoning module; `BootstrapFewShot` per stage + LLM-judge metric | Reasoning-operator bank ships inline; vendor a small BBH JSON fixture | ⭐ Only reasoning-*composition* showcase found. **Code lives only in the Colab** (the `kailashsp/SELF-DISCOVER` GitHub repo is NOT this — no DSPy). Pinned to old API; modernize during port. |

**Suggested first port:** **#1 react2rlm** (exercises both ReAct + RLM, the
biggest gap; Deno sandbox is already what dspy4s uses) or **#10 Self-Discover**
(most novel, paper-backed — but lift the Colab code + modernize the API first).

---

## Tier 2 — strong secondary candidates (by theme)

### More RLM (for a full RLM showcase suite)
- [halfprice06/huberman-rlm](https://github.com/halfprice06/huberman-rlm) (19) — QA over 180 podcast transcripts via RLM.
- [halfprice06/rlmgrep](https://github.com/halfprice06/rlmgrep) (21) — natural-language grep over files; text path ports cleanly, PDF/Office converters need stubs.
- [dbreunig/dspy-monty-interpreter](https://github.com/dbreunig/dspy-monty-interpreter) (21) — pluggable RLM interpreter; a **design reference** for dspy4s's interpreter abstraction, not a code lift.
- [alishivani666/RLMOptimizer](https://github.com/alishivani666/RLMOptimizer) (22) — RLM-*as-optimizer* (agentic alternative to fixed search). Advanced.
- [manojlds/dspy-deepagents](https://github.com/manojlds/dspy-deepagents) (22) — "deep agents" on the RLM REPL loop with filesystem tools + sub-agent delegation.
- [Archelunch/dspy-repl](https://github.com/Archelunch/dspy-repl) (23) — non-Python REPL engines (Scheme/SQL/Haskell/JS) for RLM; the sqlite SQL engine is the cleanest port.
- [large-scale-ai-systems/DSPyResearchAgent](https://github.com/large-scale-ai-systems/DSPyResearchAgent) (22) — RLM code-gen loop for multi-constraint planning.

### More GEPA
- [HuggingFace Cookbook: DSPy GEPA](https://huggingface.co/learn/cookbook/en/dspy_gepa) (7) — math reasoning, baseline vs optimized.
- [mahopman/dspy-trusted-monitor](https://github.com/mahopman/dspy-trusted-monitor) (6) — GEPA flags backdoored vs honest code; clean train/eval/analyze split.
- [shcallaway/dspy-gepa-example](https://github.com/shcallaway/dspy-gepa-example) (15) — minimal GEPA reference (sentiment + QA).
- [TheDataQuarry "Learning DSPy 3"](https://thedataquarry.com/blog/learning-dspy-3-working-with-optimizers/) (8) — M&A extraction, Bootstrap + GEPA, 34-example bundled set.
- ["I Taught a Small LLM to Write Fiction"](https://meandnotes.substack.com/p/i-taught-a-small-llm-to-write-fiction) → [Archelunch/creative-optimisation-tutorial](https://github.com/Archelunch/creative-optimisation-tutorial) (7) — GEPA + judge metric for creative writing; nice narrative.
- [kp27302/DSPy-GEPA-BI](https://github.com/kp27302/DSPy-GEPA-BI) (6) — multi-module SQL→KPI→insight pipeline, GEPA across accuracy/cost/latency.

### MIPROv2 / optimizer demos
- [ganarajpr/sanskrit-translator-dspy](https://github.com/ganarajpr/sanskrit-translator-dspy) (2) — MIPRO translation + check signature, bundled data, Ollama.
- [alibabadoufu/dspy-bench](https://github.com/alibabadoufu/dspy-bench) (9) — optimizer benchmarking harness over JSONL with configurable metrics.
- [dustinober1/DSPY](https://github.com/dustinober1/DSPY) (22) — optimizer notebooks on GSM8K/HotPotQA, Ollama.
- [haizelabs/dspy-redteam](https://github.com/haizelabs/dspy-redteam) (awesome) — ~165-LOC MIPROv2 jailbreak optimizer, ships 50 AdvBench goals. **Caveat:** judge layer uses `verdict`+`instructor`+a Claude-native judge — rewrite as plain Predict calls; can't run free/offline as-is.
- [vintrocode/dspy-opentom](https://github.com/vintrocode/dspy-opentom) (awesome) — CoT + `BootstrapFewShotWithRandomSearch` on theory-of-mind QA. Ships `.pkl` data → needs JSON re-export.
- [scottmreed/chemistry-augmented-generation](https://github.com/scottmreed/chemistry-augmented-generation) (awesome) — MIPROv2 + typed predictor, ships offline CSVs. **Caveat:** SMARTS feature-engineering needs RDKit → use CDK or precompute into the CSV.

### Classification / extraction / typed signatures
- [TheDataQuarry "Power of Good Abstractions"](https://thedataquarry.com/blog/learning-dspy-1-the-power-of-good-abstractions/) → [prrao87/dspy-intro](https://github.com/prrao87/dspy-intro) (1) — classify + structured extract + module composition with branching.
- [Mindfire "How DSPy Builds Prompts"](https://www.mindfiretechnology.com/blog/archive/dspy-how-it-works/) (16) — `inspect_history`, how a signature compiles into the actual prompt. Pedagogical.
- [support_sam](https://github.com/haasonsaas/dspy-0to1-guide/blob/main/examples/personas/support_sam.py) (1) — CoT support agent (classify → retrieve → respond → CSAT) with an in-file TF-IDF retriever.

### Agents / ReAct patterns
- [joelgrus/human-in-the-loop-dspy-tool](https://github.com/joelgrus/human-in-the-loop-dspy-tool) (3) — "ask a human" as a ReAct `Tool` (blocking callback).
- [Elicited: per-module token tracking](https://www.elicited.blog/posts/dspy-track-token-usage-per-module) (15) — callbacks attribute tokens to nested modules. **Novel** — nothing like it in dspy4s yet; fits the RuntimeContext usage model.
- [Elicited: managing tools](https://www.elicited.blog/posts/managing-tools-in-dspy) (14) — typed tool contracts + ReAct, production composition.
- [Elicited: status streaming](https://www.elicited.blog/posts/dspy-status-streaming) (14) — progressive status updates from a ReAct agent; flag streaming-API gaps first.
- [benvenker/dspy-agents](https://github.com/benvenker/dspy-agents) (10) — RAG agent compiled with MIPROv2, Evaluate harness, MCP tool server/client, ~28-example jsonl.

### Reasoning structures
- [marcusjihansson/dspy-tree-of-thoughts](https://github.com/marcusjihansson/dspy-tree-of-thoughts) (10) — ToT with pluggable BFS/DFS/MCTS/A* search over DSPy generate+evaluate.
- [ziyacivan/braid-dspy](https://github.com/ziyacivan/braid-dspy) (15) — bounded reasoning: emit a machine-readable flowchart, then execute it.

### Adapters / serialization
- [BAML adapter](https://www.btbytes.com/BAML) (9) — a custom adapter for higher-quality structured output (a *new adapter* to implement, like TOON).
- [Maxime Rivest: system-prompt optimization](https://maximerivest.com/posts/automatic-system-prompt-optimization.html) (3) — subclass the adapter so the optimizer targets a plain system prompt instead of DSPy's field template.

### RAG content pipeline / multi-stage
- [weaviate-tutorials/Hurricane](https://github.com/weaviate-tutorials/Hurricane) (awesome) — 5×CoT blog-writing pipeline + retrieval + `BootstrapFewShot`, bundled 90-file `.mdx` corpus. Port the in-process `forward()` with a local retriever; drop FastAPI/React/Weaviate. (The "generative feedback loop" branding is just a `for topic in outline` loop — no special mechanism.)
- [KazKozDev/dspy-optimization-patterns](https://github.com/KazKozDev/dspy-optimization-patterns) (13) — teacher-student compilation: strong model compiles few-shot+instructions into versioned JSON, cheap model serves inference.
- [Databricks: multi-prompt optimization](https://learn.microsoft.com/en-gb/azure/databricks/mlflow3/genai/tutorials/examples/multi-prompt-optimization) (13) — GEPA optimizing two chained prompts jointly. Re-express as a dspy4s 2-module program (the doc's code targets MLflow `optimize_prompts`, not raw DSPy).

### Larger flagship (later, gated)
- [stanford-oval/storm](https://github.com/stanford-oval/storm) (awesome) — the famous research-to-cited-article system; still real DSPy (`dspy_ai==2.4.9`), but its DSPy surface is just Predict+CoT orchestration and it **cannot run without live web search + Wikipedia scraping**. A trimmed "STORM-wiki-lite" (persona → perspective conversation → outline → article → polish, with `Parallel`) is a good showcase **once a real Scala search retriever exists**.

---

## Blocked / skip (with reason)

- **mem0 integrations** — mem0 is out of scope. (The "from-scratch" memory-layer
  tutorial [avbiswas/mem0-dspy](https://github.com/avbiswas/mem0-dspy) (21) is the
  exception — it explicitly does *not* use the mem0 lib, so it's portable.)
- **SIMBA-based** — [MorningStarTM/Synthetic-Data-Generator](https://github.com/MorningStarTM/Synthetic-Data-Generator) (18); SIMBA isn't ported.
- **Fine-tuning / RL** — AUTODSPy, `nafew-azim/AUTODSPy` (11), [leockl/vidspy](https://github.com/leockl/vidspy) (20, VBench video metric), Nemotron training (22).
- **Theorem-prover dependent** — [evalops/cognitive-dissonance-dspy](https://github.com/evalops/cognitive-dissonance-dspy) (1, needs Coq/Rocq).
- **Heavy Python-stack apps where DSPy is a thin slice** — Qdrant/Weaviate/FastAPI/Flask multimodal pipelines (aisha, QdrantRAG, MedSage, intellyweave, etc.).
- **Products/frameworks, not examples** — [khezen/codespy](https://github.com/khezen/codespy) (DSPy is a thin ReAct runtime in an 11k-LOC product; nanocode already covers the pattern), [Strategic-Automation/arachne](https://github.com/Strategic-Automation/arachne) (DSPy inseparable from a custom DAG runtime + browser-use + Langfuse).
- **Depends on a removed module** — the [Synthesizer Colab](https://colab.research.google.com/drive/1CweVOu0qhTC0yOfW5QkLDRIKuAuWJKEr) (awesome) uses `dspy.experimental.Synthesizer`, removed upstream and absent in dspy4s.
- **Non-Python reimplementations** — `karthikscale3/dspyground` (20, TS), `wangjing0/gepa-optimizer` (5, standalone non-DSPy), CocoIndex+LanceDB (18, Python/Rust-native).
- **Dead links** — `vedant007-v/codex_dspy` (404), `evalops/founder-email-optimizer` (404), `myanvoos/coview` (404).
