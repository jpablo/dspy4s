# dspy4s vs kyo-ai: feature comparison

**Status:** reference (not a plan)
**Date:** 2026-10-21 (first version 2026-06-26)
**Subjects:**
- dspy4s: this repo, branch `3.4.0` at `f3f811ef` (DSPy 3.4.0 parity work; it descends from `main`, and
  `refactor/composite-primitives` is merged into it).
- kyo-ai: `getkyo/kyo`, module `kyo-ai` on `main` at `432aac1f6` (2026-10-11). Local clone
  `/Users/jpablo/GitHub/kyo`, paths `kyo-ai/shared/src/main/scala/kyo/*`. Grounded on the module README and
  the source (`AI`, `Agent`, `LLM`, `Tool`, `Thought`, `Mode`, `Observe`, `Decider`, `Prompt`, `AISession`,
  `AIEnv`, `AIException`, `ai/Config`, `ai/DeciderConfig`, `ai/Context`, the completion backends).

Related: the effect-substrate evaluation (kyo-compat) is in
[composite-primitives.md](composite-primitives.md#step-6-substrate-kyo-compat-evaluated-not-yet-adopted).

## Summary

Both projects grew since June, and the shared area is now larger. The overlap was "one generation": I/O, a
tool-call loop, structured reasoning, provider configuration, retry, parallel calls, streaming, and a
structured error model. Since then both projects added typed decisions with a TypeSafe backend, tools from
an MCP server, token usage tracking, and some form of multi-turn history.

The upper layers still do not overlap:

- **dspy4s** has the *optimizer and compiler* layer: teleprompters, evaluation, few-shot demos, and a
  `Signature` that a program can change. A program is a value that you compile against a metric.
- **kyo-ai** has the *conversation, agent, and effect-runtime* layer: remembering instances, actor agents,
  sessions, and middleware, built on the Kyo effect system. An LLM call is an effectful value that you
  compose.

kyo-ai is still a reference design for the runtime primitives that dspy4s does not have. It is not a
dependency of dspy4s, and dspy4s does not compete with it.

## What changed since 2026-06-26

**kyo-ai** (merged into kyo `main` as #1707 on 2026-06-26, then extended):
- Command-backed harness providers: Claude Code and Codex (#1728). With xAI and Moonshot, the module now
  has eleven providers behind four backend adapters (OpenAI-compatible HTTP, Anthropic HTTP, Claude Code,
  Codex).
- `Observe`, a fifth enablement kind, and `AIStats` token counts per scope or per instance (#1770).
- MCP tools: `Tool.fromMcp(client)` and `Tool.initDynamic` for a schema held as a value (#1894).
- `Decider`: typed decisions (`check`, `choose`, `score`, `batch`) with a calibrated TypeSafe backend
  (#1955).
- A model catalog that declares facts per model: context window, output maximum, reasoning encoding
  (token budget, self-sized, graded level, provider-managed, none), temperature and image support.
  `modelStreamsIncrementally` tells if an endpoint streams token by token.
- More precise failures: `AIRateLimitException` (with `Retry-After`), `AIProviderAuthException`,
  `AIOutputLimitException`, `AICompletionTimeoutException`, `AIHarnessException`, and others (#2136).
- Not merged: branch `ai-compaction` (2026-07-28) adds a `Compactor` enablement that keeps long sessions
  inside the context window. It keeps the full transcript and shows the model a compacted view, with a
  `recall` tool and a local tiktoken tokenizer.

**dspy4s:**
- The OpenAI provider also speaks the Responses API (`LmMode.Responses`), streaming included.
  `TypeSafeModel` is a second provider, for decisions only.
- Native function calling is an adapter option (`ChatAdapter`, `JSONAdapter`). The experimental `ReActV2`
  uses it when the adapter and the model support it. `ReAct` stays on the text protocol.
- `CodeAct` and `ProgramOfThought` are removed. `RLM` is the code agent.
- New: `History` as a signature input, the `mcp` module, experimental decision outputs (`Noul`, `Score`,
  `Choice`) with `DecisionAdapter`, `TwoStepAdapter`, usage and cost tracking, the control-only `Mode`
  combinator, and the optimizers `KNNFewShot`, `InferRules`, `Ensemble`, and `ReAnchor`.

## Common features (present in both today)

| Feature | kyo-ai | dspy4s | Sameness |
|---|---|---|---|
| **Structured output** (derive a schema, make the model fill it, decode) | `AI.gen[A]` with `A: Schema` (kyo-schema) → result tool → decode; `StrictSchema` makes the strict form where a provider needs it | `Predict[I,O]` with `Signature` + `Shape` (zio-blocks `Schema`) → adapter (Chat, JSON, XML, TwoStep) → decode | same idea; different codec |
| **Inputs** (a structured value encoded into the request) | `gen[A](input: B)` encodes `B: Schema` as JSON in a user message (1 to 4 inputs) | `Signature.inputShape.encode` → record → the adapter formats it | same idea |
| **Tool abstraction and agent loop** (show tools, dispatch, decode arguments with the tool schema, send the result back, keep tool failures as messages) | `Tool.init` + eval loop: a bad decode gives a corrective system message, a throw gives a tool message | `ToolFunction` + `ReAct` / `ReActV2` (`ToolExecutor.invoke`; a tool error becomes an observation) | same idea; different protocol (see below) |
| **Tools from an MCP server** | `Tool.fromMcp(client)`; `Tool.initDynamic` checks arguments against the published schema | `mcp` module: `McpToolClient`, `McpTools.convert` (MCP Java SDK) | same idea |
| **Reasoning as output fields** | `Thought.opening` / `Thought.closing` (any number of typed fields, `@doc` gives field instructions, a `process` hook); `Thought.reflective` | `ChainOfThought` prepends `reasoning: String` through `OutputAugmentation.PrependField`; `MultiChainComparison` uses the same helper | same idea; kyo-ai is more general (closing fields, typed reasoning, hooks) |
| **Typed decisions** (yes/no, choice, score with a probability distribution) | `Decider.check` / `choose` / `score` / `batch`; also on an instance (`ai.choose`), where the decision joins the history | `Noul` / `Score` / `Choice` output types, `DecisionAdapter`, `DecisionPlan`; `ReAnchor` calibrates thresholds, cuts, and weights | same idea and the same vendor primitives; dspy4s can also optimize the cut points |
| **TypeSafe decision backend** | `AI.DeciderConfig.TypeSafe` (`jevLatest`, `jevPreview`), used by default when `TYPESAFE_API_KEY` is set | `TypeSafeModel` (`/v1/systemone`, `jev-latest`) | same; without TypeSafe, the main model writes the distribution as JSON in both |
| **Provider-agnostic LM interface** | `AI.Config.Provider`, eleven providers on four backend adapters | `LanguageModel` trait; OpenAI (Chat and Responses), any OpenAI-compatible server through `baseUrl`, `TypeSafeModel` | abstraction common; breadth differs (dspy4s has no Anthropic adapter) |
| **Model capabilities** | catalog entry per model: context window, output maximum, reasoning encoding, temperature, images, incremental streaming | `LmCapabilities` (function calling, response schema), selected by `baseUrl` or the `capabilities` field | kyo-ai declares much more per model |
| **Config and runtime settings** (temperature, seed, timeout, iteration cap, reasoning) | `AI.Config` copy-on-write builders; `reasoningBudget`, `reasoningLevel`, `disableReasoning` | the `config` bag per call or per module (`DynamicValue.Record`) + `RuntimeContext` settings; `Mode` rewrites the bag | same idea; typed builders vs an untyped record |
| **Retry and timeout around the LM call** | eval loop: meter → retry → timeout; waits for `Retry-After` | `RetryPolicy` / `RetryPolicies` (jitter, `retry-after`) in `ManagedLanguageModel` | same idea |
| **Cache** | none of its own (it reads the provider's cached-token counts) | `LmCache`: in-memory, disk (with culling), composite | dspy4s only, see below |
| **Parallel generation** | `LLM.given` + `Async.foreach` / `fill` / `race`; the asymmetric `Isolate` merges each conversation | `Parallel` combinator + `ParallelExecutor` (thread pool, context propagation) | same idea; fibers vs threads |
| **Streaming** | `AI.stream[A]`: async `Stream`, text chunks for `String`, complete objects for other types | `Streamify`: a producer thread and a blocking `ClosableIterator[StreamEvent]` with per-field tokens; SSE for Chat and Responses | both have it; async vs blocking |
| **Token usage** | `Observe.withStats` → `AIStats` (input, output, cached, reasoning tokens) per scope or per instance; `Observe.init` callbacks can stop a generation | `LmUsage`, `UsageTracker`, `RunUsage`, `LmPrice` / `LmCost` in the history entry | same idea; kyo-ai adds a typed guardrail hook, dspy4s adds a cost estimate |
| **Multi-turn history** | an `AI` instance keeps its own `Context`; `AI.Context derives Schema` | `History(messages)` as a signature input; adapters replay it, tool calls included | partial: kyo-ai keeps the history, dspy4s gets it from the caller |
| **Structured error hierarchy** | sealed `AIException` (`AIGenException`, `AIStreamException`, provider, transient, rate-limit, output-limit, harness leaves) on `Abort` | sealed `DspyError` with `LmError` and `LmProviderError` (auth, billing, rate limit, context window, timeout, ...) as `Either` values | same idea; effect channel vs values |
| **Instructions to the model** | `Prompt`: composable, a primary instruction plus a reminder at the end of the context, `andThen` merge, effectful text | instructions on `SignatureLayout` (`withInstructions`, `appendInstructions`) + field descriptions + `ReAct` `buildInstructions` | partial: a composable `Prompt` vs instructions bound to the signature |

## Where "common" needs an asterisk

Same concept, but a different mechanism:

- **Tool calling.** kyo-ai uses native function calling on the HTTP backends. The command harnesses
  (Claude Code, Codex) get the tools, the result tool included, from an in-process MCP server. In dspy4s, native function calling is an adapter option. `ReActV2` uses it
  when it can; `ReAct` and `RLM` use a text protocol.
- **Errors.** kyo-ai puts failures on the effect channel (`Abort[AIGenException]`). dspy4s keeps them as
  `Either[DspyError, A]` values. Because both are typed and sealed, a future
  `CIO[Either[DspyError, A]]` substrate can carry the dspy4s values unchanged.
- **Streaming.** kyo-ai gives an async `Stream` of text or complete objects, and each catalog entry says if
  its endpoint streams token by token. dspy4s gives a blocking iterator of per-field token events.
- **Mode.** kyo-ai `Mode` receives the generation as a value and can run it zero, one, or many times (for
  example, sample in parallel and synthesize). dspy4s `Mode` only rewrites the per-call controls (the
  config bag, `rolloutId`, `traceEnabled`). In dspy4s, a step that runs its own learnable predict
  (synthesis, comparison, critique) is a dedicated combinator, so that an optimizer can see that predict.
- **History.** A kyo-ai instance accumulates its history during one `LLM.run`, and an `Agent` keeps it
  across `ask` calls. A dspy4s `History` is an input value: the caller builds it and passes it in.

## Not shared (the boundary)

**dspy4s only:**
- Optimizers and teleprompters: `BootstrapFewShot` (+ random search), `LabeledFewShot`, `KNNFewShot`,
  `COPRO`, `MIPROv2`, `InferRules`, `Ensemble`, `ReAnchor`, GEPA (own module), `ParaCompile`.
- Evaluation, metrics, and LLM-as-judge (`evaluate` module).
- Few-shot `Example` values and demos; `ProgramPersistence` for compiled programs.
- A `Signature` / `SignatureLayout` value that programs change (augment, dump, optimize the instructions).
- Callbacks (`CallbackHandler`), `TraceEntry` / `HistoryEntry` observability in `RuntimeContext`.
- The code agent `RLM` with its interpreters (Deno/Pyodide, subprocess Python, local Python).
- Context-window truncation (`ContextWindowExceededError` + `TrajectoryTruncation` drops the oldest steps).
- Pluggable text adapters (Chat, JSON, XML, TwoStep, Decision).
- The LM cache (`LmCache`: in-memory, disk, composite) and cost estimates (`LmPrice`).
- The OpenAI Responses API.

**kyo-ai only:**
- Conversation memory: an `AI` instance accumulates its `Context` across turns. `AI.forget` and `AI.fresh`
  roll back or blank the history for a block.
- Long-lived actor `Agent`s (addressable through `ask`; the conversation lives longer than one run).
- Session `snapshot` / `recover`; `AI.Context derives Schema` (a serializable conversation).
- One `Enablement` binder for Tool, Prompt, Thought, Mode, and Observe (`AI.enable` on a scope, or
  `ai.enable` on an instance).
- `Mode` middleware that wraps the generation (parallel sample and synthesize, model switch, pre/post
  steps).
- A typed guardrail through `Observe` (an `Abort[E]` in the observer stops the generation).
- Built on an effect system (`LLM extends ArrowEffect`; `Async` / `Abort` / `Scope` / `Isolate`).
- Eleven providers, including Anthropic, Gemini, and the Claude Code and Codex command harnesses.
- A model catalog with declared reasoning encodings and output ceilings.
- Image inputs (`AI.Image` on a user message).
- Async streaming of complete objects.
- In progress on a branch: context compaction (`Compactor`).

## Strategic reading

The split is the same as in June: dspy4s has the **policy and compiler** layer, and kyo-ai has the
**harness and runtime** layer. The common area is now larger, and two things show this. Both projects
implement the same TypeSafe decision primitives, and both take tools from MCP servers. What this means for
dspy4s:

1. **Use kyo-ai as a reference design. Do not add it as a dependency.** These parts are worth a
   reimplementation on the dspy4s substrate:
   - `Thought` (reasoning as ordered typed fields, opening and closing, `process` hooks). dspy4s has the
     first step: `OutputAugmentation.PrependField` adds one field at the front. Closing fields and typed
     reasoning are the next step for the CoT family.
   - `Enablement` (one way to enable a tool, prompt, thought, mode, or observer on a scope or an instance).
     dspy4s composites still wire their parts one by one.
   - The model catalog (context window, output maximum, reasoning encoding per model). `LmCapabilities`
     covers two flags only. A context window per model would also let `TrajectoryTruncation` act before
     the provider refuses the request.
   - `Observe` guardrails (a token budget that stops a run). dspy4s records usage but cannot stop a run on
     it.
   - Do **not** copy the kyo-ai `Mode` shape for `BestOfN`, `Refine`, or `MultiChainComparison`. dspy4s
     decided that a step with a learnable predict is a dedicated combinator, so that the optimizers can
     reach the predict. The dspy4s `Mode` stays control-only.
2. **Do not adopt kyo-ai to get multi-effect support.** kyo-ai is for Kyo only and requires kyo-schema.
   For the cross-effect goal (Kyo, ZIO, Cats Effect), use **kyo-compat**. It does not depend on a codec,
   so dspy4s keeps zio-blocks `Schema`. See the substrate section in
   [composite-primitives.md](composite-primitives.md#step-6-substrate-kyo-compat-evaluated-not-yet-adopted).
   That evaluation is still open; dspy4s has no kyo dependency today.
3. **Decisions are the first area where the two can be compared directly.** Both use the TypeSafe
   `noul` / `choice` / `score` primitives. kyo-ai adds decisions on a conversation (the decision joins the
   history). dspy4s adds calibration of the cut points (`ReAnchor`) and decision outputs inside a
   signature. Compare the two APIs before the dspy4s decision API leaves the experimental state.
4. **The conversation and agent layer is still the real gap.** `History` lets a caller pass earlier turns,
   but dspy4s has no instance that remembers, no long-lived agent, and no session persistence. If dspy4s
   grows toward a general agent, use kyo-ai `AI` / `Agent` / `AISession` as the reference for memory,
   persistence, and long-lived entities. Use the `ai-compaction` branch as the reference for long sessions
   if it merges.

   The code harness proposal ([CODE_HARNESS.md](../CODE_HARNESS.md)) covers most of this gap for a coding
   agent. The newest version, with section 7 "Sessions and the controller", is on branch `code-harness`.
   - A session is an append-only log, kept as a value and in a JSONL file. A pure projection makes the
     `History` of each turn.
   - Pluggable context strategies keep a long session inside the context window and never change the log.
   - A controller conversation that never ends starts work sessions, and it uses `tell` and `observe` on
     them.
   - The policy, compactor, and sub-query steps are `Predict` leaves, so the optimizers can tune them.

   The proposal does not give a conversation wrapper for ordinary programs, and it does not merge
   conversations from parallel branches. The `ai-compaction` design is close to its memory tree: a full
   transcript, a compacted view, and a `recall` tool.

In one sentence: the two projects share the single generation and now also decisions and MCP tools;
above that, dspy4s compiles programs and kyo-ai keeps conversations.
