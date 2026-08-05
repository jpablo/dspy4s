package dspy4s.adapters.contracts

import dspy4s.core.contracts.{AdapterRef, DspyError, RuntimeContext, SignatureLayout}
import dspy4s.lm.contracts.{LanguageModel, LmOutput}

trait Adapter extends AdapterRef:
  def name: String

  def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt]

  def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput]

  /** Streaming-aware adapters override this to return a per-call state machine. The default returns [[None]] and the
    * streaming pipeline falls back to emitting raw tokens with an empty field name.
    */
  def streamingState(layout: SignatureLayout): Option[AdapterStreamingState] = None

  def execute(languageModel: LanguageModel, invocation: AdapterInvocation)(using
      RuntimeContext
  ): Either[DspyError, Vector[ParsedOutput]] =
    for
      prompt <- format(invocation)
      // Merge adapter-contributed requestOptions UNDER the request's existing options (per-call/module wins).
      mergedOptions = FormattedPrompt.mergeOptions(prompt.requestOptions, invocation.request.options)
      response <- languageModel.call(invocation.request.copy(messages = prompt.messages, options = mergedOptions))
      parsed   <- parseOutputs(invocation.layout, response.outputs)
    yield parsed

  private def parseOutputs(layout: SignatureLayout, outputs: Vector[LmOutput])(using
      RuntimeContext
  ): Either[DspyError, Vector[ParsedOutput]] =
    outputs.foldLeft(Right(Vector.empty): Either[DspyError, Vector[ParsedOutput]]) { (acc, output) =>
      for
        soFar  <- acc
        parsed <- parse(layout, output)
      yield soFar :+ parsed
    }
