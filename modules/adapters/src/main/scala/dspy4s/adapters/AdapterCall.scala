package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.collections.Traverse
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.CallbackDispatcher
import dspy4s.core.runtime.StreamProgress
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmResponse

/** One call of an adapter (upstream `ChatAdapter.__call__`): format, call the model, parse, and one fallback. The two
  * callers are `PredictEngine` and the extraction step of [[TwoStepAdapter]]. The format and parse steps send the
  * adapter callback events (upstream `with_callbacks` on `Adapter.format` and `Adapter.parse`), and the model call
  * sends the LM callback events ([[LanguageModel.callAndRecordUsage]]).
  */
private[dspy4s] object AdapterCall:

  /** The response of the model and its parsed outputs. */
  final case class Answer(response: LmResponse, parsed: Vector[ParsedOutput])

  /** The response of the model and the result of the parse step. */
  private final case class Attempt(response: LmResponse, parsed: Either[DspyError, Vector[ParsedOutput]]):
    def answer: Either[DspyError, Answer] = parsed.map(Answer(response, _))

  /** Format, call the model, and parse with `adapter`. After a [[ParseError]] of the parse step, do the three steps one
    * more time with [[Adapter.fallback]] (upstream `ChatAdapter.__call__`). A call that streamed output to a listener
    * gets no second call. An error of the format step or of the model gives no second call.
    *
    * The steps run with `model` as the model of the context, also when `Predict.withLm` pins a model that is not the
    * model of the context. The adapters read the capabilities of the model from the context: the response schema of the
    * JSON adapter, and native function calling. The stream state of a streamed call
    * ([[dspy4s.adapters.contracts.Adapter.streamingState]]) gets the context of the model call, thus the same model.
    */
  def run(adapter: Adapter, model: LanguageModel, invocation: AdapterInvocation)(using
      context: RuntimeContext
  ): Either[DspyError, Answer] =
    given RuntimeContext = context.withServices(context.services.copy(lm = Some(model)))
    StreamProgress.withScope { progress =>
      callModel(adapter, model, invocation).flatMap { attempt =>
        attempt.parsed match
          case Left(error: ParseError) if !progress.emitted =>
            adapter.fallback match
              case Some(next) => callModel(next, model, invocation).flatMap(_.answer)
              case None       => Left(error)
          case _ => attempt.answer
      }
    }

  /** Format with `adapter`, call the model, and parse. The result keeps the result of the parse step apart, so the
    * caller can tell a parse error from an error of the other steps. The usage of the reply goes to the usage scope of
    * the call before the parse ([[LanguageModel.callAndRecordUsage]]), so an attempt whose reply fails to parse counts
    * too (upstream records the usage of each LM call, `dspy/clients/execution.py`, `finalize`).
    */
  private def callModel(adapter: Adapter, model: LanguageModel, invocation: AdapterInvocation)(using
      RuntimeContext
  ): Either[DspyError, Attempt] =
    for
      prompt <- CallbackDispatcher.withAdapter(
                  adapterName = adapter.name,
                  inputs = DynamicValues.record("phase" := "format", "signature" := invocation.layout.name)
                ) {
                  adapter.format(invocation)
                }
      // G-7: merge the adapter-contributed request options (e.g. `response_format`) UNDER the existing request
      // options, so explicit per-call/module config wins on key collision. The options that the adapter removes
      // (`FormattedPrompt.removedOptions`) go first.
      response <- LanguageModel.callAndRecordUsage(
                    model,
                    invocation.request.copy(
                      messages = prompt.messages,
                      options = prompt.options(invocation.request.options)
                    )
                  )
    yield Attempt(response, parseOutputs(adapter, invocation, response.outputs))

  /** Parse each output with `adapter` (`Adapter.parse` with `invocation`, the invocation of the request). */
  private def parseOutputs(adapter: Adapter, invocation: AdapterInvocation, outputs: Vector[LmOutput])(using
      RuntimeContext
  ): Either[DspyError, Vector[ParsedOutput]] =
    Traverse.either(outputs.zipWithIndex) { (output, index) =>
      CallbackDispatcher.withAdapter(
        adapterName = adapter.name,
        inputs = DynamicValues.record("phase" := "parse", "index" := index)
      ) {
        adapter.parse(invocation, output)
      }
    }
