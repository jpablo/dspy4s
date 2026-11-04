package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.NativeFunctionCalling
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.adapters.internal.AdapterTextSupport
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
import zio.blocks.schema.DynamicValue

/** One call of an adapter (upstream `ChatAdapter.__call__`): format, call the model, parse, and one fallback. The two
  * callers are `PredictEngine` and the extraction step of [[TwoStepAdapter]]. The format and parse steps send the
  * adapter callback events (upstream `with_callbacks` on `Adapter.format` and `Adapter.parse`), and the model call
  * sends the LM callback events ([[LanguageModel.callAndRecordUsage]]).
  */
private[dspy4s] object AdapterCall:

  /** The check of the values of one parsed output. A `Left` means that the values do not fit the output type of the
    * caller.
    */
  type OutputCheck = DynamicValue.Record => Either[DspyError, Unit]

  /** The check that accepts each record. A caller with no output type uses it. */
  val acceptAnyOutput: OutputCheck = _ => Right(())

  /** The response of the model and its parsed outputs. */
  final case class Answer(response: LmResponse, parsed: Vector[ParsedOutput])

  /** The response of the model and the result of the parse step. */
  private final case class Attempt(response: LmResponse, parsed: Either[DspyError, Vector[ParsedOutput]]):
    def answer: Either[DspyError, Answer] = parsed.map(Answer(response, _))

  /** Format, call the model, and parse with `adapter`. After a [[ParseError]] of the parse step, do the three steps one
    * more time with [[Adapter.fallback]] (upstream `ChatAdapter.__call__`). A call that streamed output to a listener
    * gets no second call. An error of the format step or of the model gives no second call.
    *
    * `checkOutput` is a part of the parse step, for the values of each output of the reply and in each attempt.
    * Upstream `parse_value` validates each field with its annotation in the parse step. Here the adapters read the wire
    * type of a field, and the caller has the output type. A `Left` of the check becomes a [[ParseError]] with the
    * message of the check and the text of the output as its raw response. A tool turn has one more step before that
    * error ([[checked]]).
    *
    * An empty reply ([[isEmptyReply]]) is a [[ParseError]] of the parse step in each attempt, with no parse of the text
    * (upstream `Adapter._call_postprocess`). Thus this rule is the same for each adapter format, and such a reply gets
    * the fallback.
    *
    * The steps run with `model` as the model of the context, also when `Predict.withLm` pins a model that is not the
    * model of the context. The adapters read the capabilities of the model from the context: the response schema of the
    * JSON adapter, and native function calling. The stream state of a streamed call
    * ([[dspy4s.adapters.contracts.Adapter.streamingState]]) gets the context of the model call, thus the same model.
    */
  def run(
      adapter    : Adapter,
      model      : LanguageModel,
      invocation : AdapterInvocation,
      checkOutput: OutputCheck = acceptAnyOutput
  )(using context: RuntimeContext): Either[DspyError, Answer] =
    given RuntimeContext = context.withServices(context.services.copy(lm = Some(model)))
    StreamProgress.withScope { progress =>
      callModel(adapter, model, invocation, checkOutput).flatMap { attempt =>
        attempt.parsed match
          case Left(error: ParseError) if !progress.emitted =>
            adapter.fallback match
              case Some(next) => callModel(next, model, invocation, checkOutput).flatMap(_.answer)
              case None       => Left(error)
          case _ => attempt.answer
      }
    }

  /** Format with `adapter`, call the model, and parse. The result keeps the result of the parse step apart, so the
    * caller can tell a parse error from an error of the other steps. The usage of the reply goes to the usage scope of
    * the call before the parse ([[LanguageModel.callAndRecordUsage]]), so an attempt whose reply fails to parse counts
    * too (upstream records the usage of each LM call, `dspy/clients/execution.py`, `finalize`).
    */
  private def callModel(
      adapter    : Adapter,
      model      : LanguageModel,
      invocation : AdapterInvocation,
      checkOutput: OutputCheck
  )(using RuntimeContext): Either[DspyError, Attempt] =
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
    yield Attempt(response, parseOutputs(adapter, invocation, response.outputs, checkOutput))

  /** Parse each output with `adapter` (`Adapter.parse` with `invocation`, the invocation of the request), and check its
    * values with `checkOutput`. The two steps are one parse phase for the callbacks. An empty reply gets the parse
    * error of upstream in that phase, and no parse. The first output that fails gives the result, so one empty output
    * of a reply with some outputs is a parse error of the reply.
    */
  private def parseOutputs(
      adapter    : Adapter,
      invocation : AdapterInvocation,
      outputs    : Vector[LmOutput],
      checkOutput: OutputCheck
  )(using RuntimeContext): Either[DspyError, Vector[ParsedOutput]] =
    Traverse.either(outputs.zipWithIndex) { (output, index) =>
      CallbackDispatcher.withAdapter(
        adapterName = adapter.name,
        inputs = DynamicValues.record("phase" := "parse", "index" := index)
      ) {
        if isEmptyReply(invocation, output) then Left(ParseError("adapter", EmptyReplyMessage, raw = Some(output.text)))
        else adapter.parse(invocation, output).flatMap(checked(invocation, output, _, checkOutput))
      }
    }

  /** `parsed` when `checkOutput` accepts its values. Upstream validates each field in the parse, and on a tool turn a
    * parse error of the text gives no text values (`except AdapterParseError: value = {}` in `_call_postprocess`). Thus
    * when the check rejects the values of a tool turn ([[isToolTurn]]), the check runs again on the values of a tool
    * turn with no text ([[AdapterTextSupport.toolTurnValues]]). If it accepts them, they replace the values of
    * `parsed`, and the evidence stays. Each other rejection is a [[ParseError]] with the message of the first check.
    */
  private def checked(
      invocation : AdapterInvocation,
      output     : LmOutput,
      parsed     : ParsedOutput,
      checkOutput: OutputCheck
  ): Either[DspyError, ParsedOutput] =
    checkOutput(parsed.values) match
      case Right(_)    => Right(parsed)
      case Left(error) => Option
          .when(isToolTurn(invocation, output))(
            parsed.copy(values =
              AdapterTextSupport.toolTurnValues(invocation.layout, output.toolCalls, DynamicValue.Record.empty)
            )
          )
          .filter(noText => checkOutput(noText.values).isRight)
          .toRight(ParseError("adapter", error.message, raw = Some(output.text)))

  /** `true` when `output` has provider tool calls and the layout of `invocation` has a tool calls field. */
  private def isToolTurn(invocation: AdapterInvocation, output: LmOutput): Boolean =
    output.toolCalls.nonEmpty && invocation.layout.outputFields.exists(NativeFunctionCalling.isToolCallsField)

  /** `true` when `output` gives nothing to parse (upstream `Adapter._call_postprocess`): its text is blank, it has no
    * typed answers, and it is not a tool turn ([[isToolTurn]]). Thus provider tool calls with no tool calls field are
    * an empty reply, as upstream.
    */
  private def isEmptyReply(invocation: AdapterInvocation, output: LmOutput): Boolean =
    output.text.trim.isEmpty && output.answers.isEmpty && !isToolTurn(invocation, output)

  /** The message of the parse error of an empty reply (upstream `_call_postprocess`). */
  private val EmptyReplyMessage = "The LM returned an empty or null response."
