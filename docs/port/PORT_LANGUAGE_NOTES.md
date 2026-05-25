# dspy4s ⇄ Python DSPy — Language Construct Differences

**Scope:** how Python idioms in DSPy translate to Scala 3 idioms in dspy4s.
This is the technical-mechanics counterpart to [`PORT_MAP.md`](PORT_MAP.md)
(which tracks renames, consolidations, and behavioral deltas).

Each entry names the Python construct, the dspy4s equivalent, where it lives
in the code, and any practical limit or trade-off the choice introduces.

> **Maintenance rule:** add an entry when porting a feature forces a *new*
> construct mapping that isn't already documented. Don't write an entry
> just because the language is different — write one when a fresh contributor
> reading the Python source would otherwise be surprised.

---

## 1. Async and concurrency

| Python | dspy4s | Where |
|---|---|---|
| `async def acall(...)` / `await` / `asyncio.gather` | `def arun(input)(using RuntimeContext, ExecutionContext): Future[Either[DspyError, A]]` on every `Module`; default impl delegates to sync `run` via `Future.successful`. | `core/contracts/Module.scala` |
| `asyncio.iscoroutinefunction` branching inside `streamify` | dspy4s `Streamify.streamify` is **sync-only**: a daemon `Thread` producer writing to a `LinkedBlockingQueue`, consumed via `ClosableIterator`. | `streaming/Streamify.scala`, `streaming/StreamingQueue.scala` |
| `async for value in output:` | `while stream.hasNext do stream.next()` | `StreamingLiveSuite` |
| `asyncio.run_coroutine_threadsafe` / `asyncify` | `ContextPropagation.wrapExecutionContext(base, captured)` — wraps an `ExecutionContext` so spawned `Future`s see the captured `RuntimeContext`. | `core/runtime/ContextPropagation.scala` |

**Trade-off:** dspy4s cannot meaningfully port Python tests that rely on
`asyncio` cooperative scheduling within a single thread (e.g. tool calls that
yield while waiting on I/O). The producer-thread model still supports
concurrent status messages alongside tool execution because the tool runs on
the producer thread and status callbacks dispatch into the queue from
whatever thread emits them.

Open work: an effect-system surface (`fs2.Stream[IO, *]` / `ZStream`) and
async `Streamify` are listed in [`../STREAMING_POSTPONED.md`](../STREAMING_POSTPONED.md).

---

## 2. Error handling — exceptions vs `Either`

| Python | dspy4s | Where |
|---|---|---|
| `raise DSPyError(...)`, `try / except` | `Either[DspyError, A]` everywhere on public APIs. `DspyError` is a sealed ADT of `ParseError`, `ValidationError`, `ConfigurationError`, `RuntimeError`. | `core/contracts/Errors.scala`, used pervasively |
| `try: ... except Exception as e: raise CustomError(...) from e` | `for { x <- step1; y <- step2 } yield z` (monadic chaining); catch `NonFatal` only at runtime boundaries (`Streamify` producer thread, parser fallbacks). | `programs/Predict.scala`, `streaming/StreamingLanguageModelWrapper.scala` |
| Bare `Exception` for control flow | Never — exceptions are reserved for genuinely exceptional cases (JVM crashes, network errors below the transport boundary). | — |

**Why it matters for parity:** Python tests often assert via
`pytest.raises(SomeError)`. dspy4s tests assert
`result.left.toOption.get.asInstanceOf[ParseError]` or pattern-match the
sealed ADT. The semantic content is the same; the test shape isn't.

---

## 3. Runtime context — globals vs `using` clauses

| Python | dspy4s | Where |
|---|---|---|
| `dspy.configure(lm=..., adapter=...)` (process-global mutable state) | `RuntimeEnvironment.withSettings(SettingsData(...)) { ... }` (scoped, stack-based, ThreadLocal-backed) | `core/runtime/RuntimeEnvironment.scala` |
| `with dspy.context(lm=...): ...` | same `withSettings` block — no `with`-statement analog needed because the block already scopes the override. | — |
| Implicit "current LM" lookup inside `Predict.forward` | `runtime.resolveModel(using RuntimeContext)` reads the current settings | `programs/Predict.scala` |
| Reading the active LM from anywhere | `summon[RuntimeContext].settings.entries.get(SettingKeys.languageModel.name)` | `lm/runtime/ProviderLanguageModel.scala` and similar |

The `RuntimeContext` value travels via Scala 3 `using` parameters
everywhere a Python function would have read from `dspy.settings`. Tests
explicitly establish a context with `given RuntimeContext = RuntimeEnvironment.current`.

---

## 4. Dynamic kwargs and dictionaries

| Python | dspy4s | Where |
|---|---|---|
| `def __call__(self, **kwargs)` | `final case class ProgramCall(inputs: Map[String, Any], config: Map[String, Any] = Map.empty, traceEnabled: Boolean = true)` | `programs/contracts/ProgramContracts.scala` |
| `prediction.answer`, `prediction["answer"]`, `prediction.get("answer", default)` | `prediction.values: Map[String, Any]` — accessed as `prediction.values("answer")` or via `Option`. No attribute sugar. | `core/contracts/Data.scala` |
| `**signature_fields_as_kwargs` | explicit `Map[String, Any]` construction at call sites | call sites throughout |

**Cost of the delta:** every Python test that does
`prediction.answer` becomes `prediction.values("answer")` in the port —
mechanical but visible. No type safety on the `values` map (it's
`Map[String, Any]`), which mirrors Python's runtime field access. A typed
overlay (per-signature `Prediction` subtype derived via macros) is a long-
term option that isn't on the roadmap.

---

## 5. Mutable instance state vs case-class evolution

| Python | dspy4s | Where |
|---|---|---|
| `self.demos = [...]; self.demos.append(...)` | `Predict.copy(demos = newDemos)` — immutable, `case class`-driven evolution. | `programs/Predict.scala` |
| `class Module:` with mutable attributes set in `__init__` | `final case class` with `val`s only; mutators return new instances. | every program in `modules/programs/` |
| Reflection-based state dump (`getattr` over fields) | A typeclass `PredictOps[P]` exposes the few mutable-shaped operations bootstrap optimizers need (`withDemos`, `signatureOf`, `nameOf`). | `optimize/PredictOps.scala` |

**Why:** Scala case classes give structural equality, copy, and
serialization for free. The cost is that anything Python expresses as
"reach in and mutate" has to go through an explicit lens — usually
`PredictOps` or a `withFoo` mutator method.

---

## 6. ADTs vs `isinstance` dispatch

| Python | dspy4s | Where |
|---|---|---|
| `if isinstance(value, StreamResponse): ... elif isinstance(value, StatusMessage): ...` | `sealed trait StreamEvent` with case classes `TokenEvent`, `StatusEvent`, `PredictionEvent`, `ErrorEvent`; consumed via `match`. | `streaming/contracts/StreamingContracts.scala` |
| `if isinstance(result, dict): ... elif isinstance(result, list): ...` | sealed ADTs for typed payloads; `Map[String, Any]` for the genuinely dynamic ones. | `core/contracts/Data.scala`, `lm/contracts/LmContracts.scala` |
| `Exception` hierarchy with `isinstance` checks | `sealed trait DspyError` ADT with `match`/`asInstanceOf`. | `core/contracts/Errors.scala` |

Pattern matching also gives exhaustiveness — the compiler tells us when a
new event type isn't handled. Python `isinstance` chains silently fall
through.

---

## 7. Protocols / duck typing vs typeclasses

| Python | dspy4s | Where |
|---|---|---|
| `class Adapter(Protocol): def parse(self, ...) -> ...` (structural) | `trait Adapter` (nominal subtyping) | `adapters/contracts/AdapterContracts.scala` |
| Operating on "anything with a `signature` attribute" | `given PredictOps[P]` typeclass instances; explicit `using` requirement at call sites. | `optimize/PredictOps.scala` |

**Trade-off:** Python's structural typing is more permissive — a third-
party adapter library doesn't need to import or extend our trait. In
dspy4s anything that wants to be an `Adapter` must extend the trait.
A `given` instance can bridge an unrelated type into a typeclass at
need, but most call sites in dspy4s just take the trait directly.

---

## 8. Decorators — both as syntax and as test markers

| Python | dspy4s | Where |
|---|---|---|
| `@pytest.mark.llm_call` (skip unless `--llm_call`) | `assume(apiKey.isDefined && hasOptIn, "...")` at the top of each live test; `assume` reports as `skipped` in MUnit when false. | `streaming/StreamingLiveSuite.scala`, `lm/providers/OpenAiLiveSuite.scala` |
| `@pytest.fixture def lm_for_test():` | A `private def buildLm(): OpenAiLanguageModel` helper at the top of each live suite, called inside the test body. | same |
| `@pytest.mark.anyio` (async test) | not used — dspy4s tests are sync. | — |
| `@property` | `def foo: T = ...` (zero-arg defs are property-equivalent in Scala). | throughout |
| `@dataclass` | `final case class` | throughout |

---

## 9. Context manager protocol vs scoping methods

| Python | dspy4s | Where |
|---|---|---|
| `with self._wrap(...): result = forward(...)` | `CallbackDispatcher.withModule(moduleName, inputs) { thunk }` returning whatever `thunk` returns; pushes/pops state in `try / finally`. | `core/runtime/CallbackDispatcher.scala`, `core/runtime/ActivePredictContext.scala` |
| `contextlib.contextmanager` | A method that takes a by-name `thunk: => A` and wraps it. The Scala idiom — no library needed. | as above |

Same shape; different syntax. No `with` keyword in Scala 3 in the
context-manager sense — the equivalent is a function that takes a thunk.

---

## 10. Reflection-based field introspection vs explicit naming

| Python | dspy4s | Where |
|---|---|---|
| `predict_name` derived from `self.predict1` field name via `__getattribute__` walking | `Predict(signature, name = Some("predict1"))` — explicit. Defaults to `"predict"`. | `programs/Predict.scala`, [`PORT_MAP.md` §4](PORT_MAP.md#4-behavioral-deltas) |
| Iterate `__dict__` to collect submodules for `named_parameters()` | Not ported. Optimizers use the `PredictOps[P]` typeclass to read each program's `demos` / `layout` directly, bypassing the generic walk entirely. An earlier `ModuleGraph` / `BaseModule` port shipped in Phase 1 and was removed once `PredictOps` proved sufficient. | `optimize/PredictOps.scala` |

**Why:** Scala has reflection but it's runtime, slow, and a footgun. The
explicit-naming style requires more keystrokes from users (`name = Some(...)`)
but never surprises with reflection failures.

---

## 11. Iterators and lazy sequences

| Python | dspy4s | Where |
|---|---|---|
| `yield` / generators | `Iterator[A]` returned from `def stream(...)`; lazy by default. | `lm/contracts/LmStreaming.scala`, `streaming/StreamingQueue.scala` |
| `AsyncIterator` / `async for` | `ClosableIterator[A]` (sync) — extends both `Iterator[A]` and `AutoCloseable`. | `core/contracts/ClosableIterator.scala` (interface lives in core; consumed by streaming) |
| `for value in stream:` with implicit cleanup | Explicit `try { while iter.hasNext do ... } finally iter.close()` or `Using(iter) { ... }` (Scala stdlib `scala.util.Using`). | callers |

---

## 12. Module / file / package organization

| Python | dspy4s |
|---|---|
| `dspy/__init__.py` re-exports the user-facing API | No equivalent. Users import the qualified name (`dspy4s.programs.Predict`). A top-level façade object could be added later. |
| One package = one directory with `__init__.py` | One package = one directory; package declaration on each file (`package dspy4s.foo`). |
| Free functions at module top level | Companion `object` methods (`Streamify.streamify`, `JsonCodec.encode`). |

Convention in dspy4s: every module has a `contracts/` subpackage holding the
public ADTs/traits, a `runtime/` subpackage for impl details, and root-level
files for the user-facing entry points. Python's flat one-file-per-class style
is collapsed into thematic files (`StreamingContracts.scala` holds
`StreamEvent`, `TokenEvent`, `StatusEvent`, etc.).

---

## 13. Typing — gradual hints vs static

| Python | dspy4s |
|---|---|
| Optional type hints (`def foo(x: int) -> str:`) | Mandatory for every signature; checked by the compiler. |
| `Any` as escape hatch | Used in dspy4s for the genuinely dynamic surfaces: `Map[String, Any]` for user inputs/outputs, `Any` for raw LM payloads in `LmChunk.raw`. |
| `Optional[T]` / `T \| None` | `Option[T]`. |
| `Union[A, B]` | Scala 3 union types `A \| B` are available; we mostly use sealed ADTs instead for closed cases. |
| `pydantic.BaseModel` for typed JSON | Currently `Map[String, Any]` + `ujson`. Pydantic-style typed inputs would require a per-signature codegen step (long-term TODO; not on the roadmap). |

---

## 14. Test mocking — `unittest.mock` vs real interfaces

| Python | dspy4s |
|---|---|
| `with mock.patch("litellm.completion") as m: ...` | A scripted `LanguageModel` test double (e.g. `ScriptedLm` in `StreamListenerSuite`) implements the real `LanguageModel`/`StreamingLanguageModel` trait. No reflective monkey-patching. |
| `unittest.mock.AsyncMock` | not applicable — see §1. |
| `dspy.utils.DummyLM` | We don't have a published `DummyLM` yet — each test rolls its own scripted LM. Could be lifted into a shared `dspy4s.testing` module if duplication becomes annoying. |

---

## 15. Things we deliberately don't do

These are choices that *could* have closer-to-Python parity but where the
Scala idiom is enough better to be worth the divergence.

- **Attribute-style access on `Prediction`.** Python writes
  `prediction.answer`; we use `prediction.values("answer")`. A
  `Selectable`/macro-derived prediction type is possible but adds compile
  time and indirection.
- **Auto-discovery of submodules via reflection.** Python walks `__dict__`;
  Scala makes it explicit (see §10).
- **Runtime monkey-patching for tests.** Tests use proper test doubles.
  See §14.
- **Async by default.** See §1. dspy4s `Streamify` is sync-only by design
  for the v1; an effect-system surface lives in the streaming roadmap.

---

## How to use this doc

If you're porting a Python file and stumble on a construct you've never
seen handled, check here. If it's not here and you decide on a non-obvious
mapping, **add it before finishing the port** — that's the simplest way to
keep the contract between Python source and Scala target legible.
