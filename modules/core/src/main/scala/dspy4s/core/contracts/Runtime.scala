package dspy4s.core.contracts

/** Marker trait for the language-model context value. Empty by design -- this trait lives in `core` so
  * [[RuntimeContext]] can name its `lm` field type, while the concrete `LanguageModel` trait (with `call` /
  * `acall`) lives in `lm/contracts` and cannot be referenced from here without inverting the module dependency
  * graph. The real [[dspy4s.lm.contracts.LanguageModel]] extends `LanguageModelRef`; downstream code (e.g.
  * `ProgramRuntime.resolveModel`) reads `ctx.lm` and narrows back to `LanguageModel` via a pattern match. */
trait LanguageModelRef

/** Marker trait for the adapter context value. Same cycle-breaking pattern as [[LanguageModelRef]]: the concrete
  * `Adapter` trait lives in `adapters/contracts` and extends `AdapterRef`, so [[RuntimeContext]] can hold an
  * adapter without `core` depending on the adapters module. */
trait AdapterRef
