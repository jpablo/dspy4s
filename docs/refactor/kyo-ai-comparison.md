# dspy4s vs kyo-ai: feature comparison

**Status:** reference (not a plan)
**Date:** 2026-06-26
**Subjects:**
- dspy4s — this repo (current `main` / `refactor/composite-primitives`).
- kyo-ai — `getkyo/kyo`, `kyo-ai` branch (local clone `/Users/jpablo/GitHub/kyo`, paths
  `kyo-ai/shared/src/main/scala/kyo/*`). Grounded on the branch's README + full source
  (`AI`, `Agent`, `LLM`, `Tool`, `Thought`, `Mode`, `Prompt`, `AISession`, `AIEnv`, `ai/Config`,
  `ai/Context`, completion backends).

Related: the effect-substrate evaluation (kyo-compat) lives in
[composite-primitives.md](composite-primitives.md#step-6-substrate-kyo-compat-evaluated-not-yet-adopted).

## TL;DR

The overlap is the **"one typed generation" core**: typed I/O, a tool-call loop, structured reasoning,
provider/config/retry/parallel/streaming, and a typed error model. Above that core the two diverge and are
**complementary**:

- **dspy4s** adds the *optimizer / compiler* layer (teleprompters, evaluation, few-shot, a manipulable
  `Signature`). It treats a program as something you compile against a metric.
- **kyo-ai** adds the *memory / agent / effect-runtime* layer (conversation instances, actor agents,
  sessions, built natively on Kyo's effect system). It treats an LLM call as a typed effectful value you
  compose.

Neither has the other's upper layer. So kyo-ai is best read as a **reference design** for the runtime
primitives dspy4s lacks, not a competitor and not a dependency.

## Common features (present in both today)

| Feature | kyo-ai | dspy4s | Sameness |
|---|---|---|---|
| **Typed structured output** (derive schema, force the model to that shape, decode) | `AI.gen[A]` with `A: Schema` (kyo-schema) → `result_tool` envelope → decode | `Predict[I,O]` with `Signature`+`Shape` (zio-blocks `Schema`) → structured-output adapter → decode | same idea; different codec |
| **Typed inputs** (structured value encoded into the request) | `gen[A](input: B)` JSON-encodes `B: Schema` into a user message | `Signature.inputShape.encode` → record → adapter formats | same idea |
| **Tool abstraction + agentic loop** (surface tools, dispatch, decode args with the tool's schema, feed result back, contain tool failures as messages) | `Tool.init` + eval loop (`Tool.internal.handle`: bad-decode → corrective system msg, throw → tool msg) | `ToolFunction` + `ReAct`/`CodeAct` loops (`ToolExecutor.invoke`; tool errors become observations) | same idea; **different protocol** (see asterisks) |
| **Structured reasoning woven into output** (CoT as typed fields, not free text) | `Thought` (opening/closing reasoning fields in the result schema, `@doc` → field instructions, `process` hook); `Thought.reflective` | `ChainOfThought` (prepend `reasoning: String`); ReAct/CodeAct extractors are CoT-augmented | same idea; kyo-ai more general (arbitrary typed reasoning, opening + closing, hooks) |
| **Provider-agnostic LM interface** | `AI.Config.Provider` + 2 wire backends (OpenAI-compatible ×6, Anthropic) | `LanguageModel` trait + options bag | abstraction common; **breadth differs**: 7 providers vs 1 (OpenAI) |
| **Config / runtime knobs** (temperature, seed, timeout, iteration cap) | `AI.Config` copy-on-write builders | per-call/per-module `config` bag (`DynamicValue.Record`) + `RuntimeContext` settings | same idea; typed builders vs untyped bag |
| **Retry + timeout around the LM call** | eval loop wraps `meter → retry → timeout` | `RetryPolicy` (+ `LmCache`) typeclasses in `lm` | same idea |
| **Parallel generation / concurrency** | `LLM.given` + `Async.foreach/fill/race`; asymmetric `Isolate` merges per-conversation state | `Parallel` combinator + `ParallelExecutor` (thread pool, context propagation) | same idea; fibers vs threads |
| **Streaming** | async SSE: prefix mode (String, token-by-token) and element mode (object-by-object) | `Streamify` synchronous per-field token streaming | both have it; **materially different** (async vs sync) |
| **Typed/structured error hierarchy** | sealed `AIException` (transport, eval-exhausted, decode, missing-key, …) on the `Abort` residual | sealed `DspyError` ADT as `Either` values | same idea; **effect channel vs values** |
| **Instructions to the model** | `Prompt` (composable, primary + floating reminder, `andThen` merge) | instructions on `SignatureLayout` (`withInstructions`) + field descriptions + ReAct/CodeAct `buildInstructions` | partial: first-class composable `Prompt` vs signature-bound instructions |

## Where "common" needs an asterisk

Same concept, materially different mechanism:

- **Tool calling.** kyo-ai is native function-calling end to end; dspy4s's actual agent loops are
  text-protocol. dspy4s *has* native-FC support at the adapter/LM level (`LmOutput.toolCalls`,
  `LanguageModel.supportsFunctionCalling`), but the loops (`ReAct`, etc.) do not use it.
- **Errors.** kyo-ai puts failures on the effect channel (`Abort[AIException]`); dspy4s keeps them as
  `Either[DspyError, A]` values. This errors-as-values alignment is what makes `CIO[Either[DspyError, A]]`
  a clean marriage at the future effect seam.
- **Streaming.** async object/text (kyo-ai) vs synchronous per-field tokens (dspy4s).

## Not shared (the boundary)

**dspy4s only:**
- Optimizers / teleprompters (BootstrapFewShot, COPRO, MIPRO, GEPA).
- Evaluation + metrics + LLM-as-judge (`evaluate` module).
- Few-shot `Example` / demos.
- A manipulable `Signature` / `SignatureLayout` artifact (augment, dump, optimize instructions).
- Callbacks / `TraceEntry` / `HistoryEntry` observability (`RuntimeContext`).
- Code execution agents: `CodeAct`, `ProgramOfThought`, `RLM` (interpreters, sandbox).
- Context-window truncation (`ContextWindowExceededError` + trajectory drop-oldest).
- Pluggable text adapters (Chat / JSON / XML).

**kyo-ai only:**
- Conversation memory (the `AI` instance accumulating `Context` across turns).
- Long-lived actor `Agent`s (addressable via `ask`, conversation outlives a run).
- Session `snapshot` / `recover`; `AI.Context derives Schema` (serializable conversation).
- The uniform `Enablement` binder (Tool / Prompt / Thought / Mode all `enableIn`).
- `Mode` generation-interception middleware (parallel sample + synthesize, model switch, pre/post).
- Built natively on an effect system (`LLM extends ArrowEffect`; `Async` / `Abort` / `Scope` / `Isolate`).
- Seven providers; async SSE streaming.

## Strategic reading

This itemizes the same complementarity at feature granularity: dspy4s owns the **policy/compiler** layer,
kyo-ai owns the **harness/runtime** layer. Consequences for dspy4s:

1. **kyo-ai is the blueprint, not a dependency.** Its runtime primitives are the cleanest in the
   ecosystem and worth reimplementing on dspy4s's own substrate:
   - `Thought` (reasoning as ordered schema fields, opening/closing, `process` hooks) is a direct upgrade
     target for the CoT family and generalizes dspy4s's fixed `reasoning: String` prepend.
   - `Enablement` (one uniform "enable on a scope/instance" trait) is the binder abstraction dspy4s's
     composites lack; it would clean up how ReAct/CodeAct/MCC wire their pieces.
   - `Mode` (generation middleware) is a cleaner model for `BestOfN`/`Refine`/`MultiChainComparison`,
     which are all "run N and synthesize."
2. **Do not adopt kyo-ai to get multi-effect.** kyo-ai is Kyo-only and forces kyo-schema. The
   cross-effect goal (Kyo/ZIO/CE) is served by **kyo-compat** instead, which is codec-agnostic so dspy4s
   keeps zio-blocks `Schema`. See the substrate section in
   [composite-primitives.md](composite-primitives.md#step-6-substrate-kyo-compat-evaluated-not-yet-adopted).
3. **The conversation/agent layer is the real gap.** If dspy4s ever grows toward a general agent (the pi
   direction), kyo-ai's `AI` / `Agent` / `AISession` are the reference for memory, persistence, and
   long-lived entities — the layer dspy4s does not have today.

The shortest statement: **the overlap is one typed generation; above it, dspy4s compiles and kyo-ai
remembers.**
