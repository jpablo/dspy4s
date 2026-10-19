# dspy4s `examples`

`examples` translates the code of the [Python DSPy documentation](https://dspy.ai) to dspy4s. Each documentation
page with code has one Scala file. The module also has demos that are not translations. It uses all the other library modules,
and sbt does not publish it.

## Conventions

- **One file for each page.** The header of a translation gives the URL of the source page and a `Status:` line. The
  status is `translated` or `blocked`.
- **The Python stays in the file.** Comment lines that start with `// |` show the Python code above its Scala
  translation.
- **A unique name for each `@main`.** For example, the email tutorial has `emailExtractionMain`. Thus two files in
  one package do not have the same entry point.
- **Parts for the documentation site.** The site includes parts of these files. A `--8<-- [start:<name>]` comment and
  a `--8<-- [end:<name>]` comment mark each part.
- **Scaffolds.** `tools/scaffold.py` makes a new file from an upstream page. It does not overwrite a file.

## Run an example

```bash
OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.email_extraction.emailExtractionMain"
```

- Give the full name of the `@main`: `dspy4s.examples.<package>.<name>`. The tables below show the two parts.
- Most examples get the model from `Demo.withLm` in `Demo.scala`. It reads `OPENAI_API_KEY` and `DSPY_MODEL` (the
  default is `gpt-5.5`).
- The examples marked "offline" make no LM call and need no key.
- Give the two MCP examples a class path as the argument. Use the output of
  `sbt -error "export examples/Runtime/fullClasspath"`.

## Translations

### `learn.programming`

| Page | `@main` | Notes |
|------|---------|-------|
| Signatures | `main` | String signatures use `Signature.fromType`. Class signatures become `Spec` traits. |
| LanguageModels | `languageModelsMain` | The LM setup, direct calls, more than one LM, and the Responses API. |
| Modules | `modulesMain` | dspy4s has no API for the `n=5` completions. |
| Tools | `toolsMain` | `ReAct` with `ToolFunction.fromMethod`, and native tool calls. |
| Adapters | `adaptersMain` | `adapter.format`, `ChatAdapter` and `JSONAdapter`, and `RuntimeEnvironment.inspectHistory`. |
| Mcp | `mcpMain` | The tools of an MCP server as `ToolFunction` values (`McpTools.fromClient`), and a `ReAct` agent. The agent starts the server `mcpArithmeticServer`. |

### `learn.evaluation`

| Page | `@main` | Notes |
|------|---------|-------|
| Data | `dataMain` (offline) | `Example`, the inputs, and the labels. `DataLoader` is not translated. |
| Metrics | `metricsMain` (offline), `metricsJudgeMain` | `FunctionMetric`, `Evaluate`, and the LM judge `SemanticF1`. |

### `learn.optimization`

| Page | `@main` | Notes |
|------|---------|-------|
| Optimizers | `optimizersMain` | `BootstrapFewShotWithRandomSearch`, and `save` and `load` with `ProgramPersistence`. |

### `deep_dive.data_handling`

| Page | `@main` | Notes |
|------|---------|-------|
| Examples | `examplesMain` (offline) | The `Example` API. An `Example` is immutable, so `withValue` returns a copy. |
| LoadingCustomData | `loadingCustomDataMain` (offline) | `Example` values from rows, and a split into a train set and a dev set. |

### `tutorials.<name>`

| Page | `@main` | Notes |
|------|---------|-------|
| `conversation_history` | `conversationHistoryMain` | A `History` input field. |
| `email_extraction` | `emailExtractionMain` | Enums and case classes with `derives Schema`, and four `ChainOfThought` programs. |
| `output_refinement` | `bestOfNAndRefineMain` | `BestOfN` and `Refine` with reward functions. |
| `streaming` | `streamingMain` | `Streamify.streamify` gives a synchronous `ClosableIterator[StreamEvent]`. Also a `StreamListener` and a `StatusMessageProvider`. |
| `async` | `asyncMain` | `ContextPropagation.future`. Async tools are not translated. |
| `cache` | `cacheMain` | A cache for each LM with `ManagedLanguageModel`. There is no global cache. |
| `observability` | `observabilityMain` | `ReAct` and a custom `CallbackHandler`. |
| `sample_code_generation` | `sampleCodeGenerationMain` | Three signatures and an agent that uses them. |
| `ai_text_game` | `aiTextGameMain` | Three signatures and the `GameAI` module. The console game is not translated. |
| `yahoo_finance_react` | `yahooFinanceReactMain` | `ReAct` with finance tools. The tools return static data. |
| `llms_txt_generation` | `llmsTxtMain` | Signatures in a pipeline. The GitHub HTTP calls are not translated. |
| `saving` | `savingMain` (offline) | `save` and `load` with `ProgramPersistence`. A small trainset replaces GSM8K. |
| `optimizer_tracking` | `optimizerTrackingMain` | `MIPROv2` with a `CallbackHandler` that counts the LM calls. |
| `mcp` | `mcpTutorialMain` | The airline MCP server `airlineMcpServer`, and a `ReAct` agent that uses its tools. The agent starts the server in a child JVM. |

### Top level (no package: `dspy4s.examples.cheatsheetMain`)

| Page | `@main` | Notes |
|------|---------|-------|
| Cheatsheet | `cheatsheetMain` (offline) | The cheatsheet snippets that dspy4s supports. Comments mark the optimizers that dspy4s does not have. |

## Other demos

| Package | `@main` | Notes |
|---------|---------|-------|
| `signatures` | `builderMain` (offline), `caseClassMain`, `functionMain`, `specMain` | The four ways to declare a signature: `Signature.builder`, `Signature.derived`, `Signature.fromType`, and `Signature.of`. |
| `tutorials.react_vs_rlm` | `reactVsRlmMain` | `ReAct` and `RLM` do one task with the same tools. The source is the MIT project [react2rlm](https://github.com/RamXX/react2rlm). `RLM` needs Deno. |
| `tutorials.talk_to_your_data` | `talkToYourDataMain`, `tytdSelfCheckMain` (offline) | An analytics agent with `RLM` and `GEPA`. The agent needs Deno. Its [README](./src/main/scala/dspy4s/examples/tutorials/talk_to_your_data/README.md) gives the details. |
| `verify` | `optimizerSmokeMain`, `gepaSmokeMain` | Runs of `COPRO`, `MIPROv2`, and `GEPA` with a real model. They read `OPENAI_MODEL` (the default is `gpt-4o-mini`). |

## Prose pages

Some upstream pages have prose and no code. For each of them, a `README.md` adapts the page to dspy4s:

- [`learn/`](./src/main/scala/dspy4s/examples/learn/README.md), with the subfolders
  [`programming/`](./src/main/scala/dspy4s/examples/learn/programming/README.md),
  [`evaluation/`](./src/main/scala/dspy4s/examples/learn/evaluation/README.md), and
  [`optimization/`](./src/main/scala/dspy4s/examples/learn/optimization/README.md)
- [`production/`](./src/main/scala/dspy4s/examples/production/README.md)
- [`tutorials/`](./src/main/scala/dspy4s/examples/tutorials/README.md), the index of the tutorials
- the index pages of the tutorial groups, in `tutorials/<name>/README.md`: `build_ai_program`, `core_development`,
  `real_world_examples`, `optimize_ai_program`, `gepa_ai_program`, `rl_ai_program`, `classification`, and `papillon`

## Blocked pages

These pages need a feature that dspy4s does not have yet. The header of each file names the feature.

| File | Feature that it needs | Alternative |
|------|-----------------------|-------------|
| `deep_dive/data_handling/BuiltInDatasets` | the dataset loaders (`dspy.datasets`) | `LoadingCustomData` |
| `learn/programming/Assertions7` | `dspy.Assert` and `dspy.Suggest` | `Refine` and `BestOfN` (`output_refinement`) |
| `tutorials/deployment` | a server for a program (FastAPI, MLflow) | Put the program in your own HTTP layer. |
| `tutorials/mem0_react_agent` | the mem0 memory store | `ReAct` (`learn/programming/Tools`) |
