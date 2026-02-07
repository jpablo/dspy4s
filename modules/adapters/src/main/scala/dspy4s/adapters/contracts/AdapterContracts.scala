package dspy4s.adapters.contracts

import dspy4s.core.contracts.AdapterRef
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.Signature
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.Message

final case class AdapterInvocation(
    signature: Signature,
    demos: Vector[Example],
    inputs: Example,
    request: LmRequest
)

final case class FormattedPrompt(messages: Vector[Message], metadata: Map[String, Any] = Map.empty)

final case class ParsedOutput(
    values: Map[String, Any],
    rawText: Option[String] = None,
    metadata: Map[String, Any] = Map.empty
)

trait Adapter extends AdapterRef:
  def name: String

  def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt]

  def parse(signature: Signature, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput]

  def execute(languageModel: LanguageModel, invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, Vector[ParsedOutput]] =
    for
      prompt <- format(invocation)
      response <- languageModel.call(invocation.request.copy(messages = prompt.messages))
      parsed <- parseOutputs(invocation.signature, response.outputs)
    yield parsed

  private def parseOutputs(signature: Signature, outputs: Vector[LmOutput])(using
      RuntimeContext
  ): Either[DspyError, Vector[ParsedOutput]] =
    outputs.foldLeft(Right(Vector.empty): Either[DspyError, Vector[ParsedOutput]]) { (acc, output) =>
      for
        soFar <- acc
        parsed <- parse(signature, output)
      yield soFar :+ parsed
    }

trait AdapterFallbackPolicy:
  def fallbackFor(error: DspyError, attemptedAdapter: String): Option[String]

object AdapterErrors:
  def missingField(fieldName: String): DspyError =
    ParseError(component = "adapter", message = s"Missing required output field: $fieldName")
