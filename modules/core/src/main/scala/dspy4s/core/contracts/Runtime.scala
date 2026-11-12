package dspy4s.core.contracts

/** Marker trait for the language-model context value. Empty by design: this trait lives in `core` so [[RuntimeContext]]
  * can name its `lm` field type, while the concrete `LanguageModel` trait (with `call` / `acall`) lives in
  * `lm/contracts` and cannot be referenced from here without inverting the module dependency graph. The real
  * [[dspy4s.lm.contracts.LanguageModel]] extends `LanguageModelRef`; downstream code (e.g.
  * `ProgramRuntime.resolveModel`) reads `ctx.lm` and narrows back to `LanguageModel` via a pattern match.
  */
trait LanguageModelRef

/** Marker trait for the adapter context value. Same cycle-breaking pattern as [[LanguageModelRef]]: the concrete
  * `Adapter` trait lives in `adapters/contracts` and extends `AdapterRef`, so [[RuntimeContext]] can hold an adapter
  * without `core` depending on the adapters module.
  */
trait AdapterRef

/** Marker trait for the stream of one `streamify` call, the analog of upstream `settings.send_stream`. Same
  * cycle-breaking pattern as [[LanguageModelRef]]: the concrete `StreamSink` trait lives in `lm/contracts` and extends
  * `StreamSinkRef`, so [[RuntimeContext]] can hold a stream without `core` depending on the lm module. The code that
  * calls a model (`StreamSink.applied`) reads `ctx.streamSink` and narrows back to `StreamSink` via a pattern match.
  */
trait StreamSinkRef

/** Marker trait for the record of the HTTP exchanges of the model calls (docs/code-harness/OBSERVABILITY.md, 4.1). Same
  * cycle-breaking pattern as [[LanguageModelRef]]: the concrete `ExchangeSink` trait lives in `lm/contracts` and
  * extends `ExchangeSinkRef`, so [[RuntimeContext]] can hold a sink without `core` depending on the lm module. A
  * provider reads `ctx.exchangeSink` and narrows back to `ExchangeSink` via a pattern match.
  */
trait ExchangeSinkRef
