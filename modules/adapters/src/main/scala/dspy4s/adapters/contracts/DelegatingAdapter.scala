package dspy4s.adapters.contracts

import dspy4s.core.contracts.{DspyError, RuntimeContext, SignatureLayout}
import dspy4s.lm.contracts.LmOutput
import zio.blocks.schema.DynamicValue

/** An adapter that wraps another adapter, [[delegate]], and gives each member of [[Adapter]] from the delegate. A
  * wrapper that extends this trait overrides only what it changes. Thus a member that [[Adapter]] gets later has one
  * place to forward it, and no wrapper hides what its delegate does.
  *
  * The fallback of a wrapper is the fallback of the delegate in the same kind of wrapper ([[wrap]]), so the rules of
  * the wrapper also apply to the second call of a predictor.
  */
trait DelegatingAdapter extends Adapter:

  /** The adapter that this wrapper wraps. */
  def delegate: Adapter

  /** This wrapper, with `next` as its delegate. */
  protected def wrap(next: Adapter): Adapter

  override def name: String = delegate.name

  override def fallback: Option[Adapter] = delegate.fallback.map(wrap)

  override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    delegate.format(invocation)

  override def parse(invocation: AdapterInvocation, output: LmOutput)(using
      RuntimeContext
  ): Either[DspyError, ParsedOutput] =
    delegate.parse(invocation, output)

  override def formatUserMessageContent(layout: SignatureLayout, values: DynamicValue.Record): String =
    delegate.formatUserMessageContent(layout, values)

  override def streamingState(layout: SignatureLayout)(using RuntimeContext): Option[AdapterStreamingState] =
    delegate.streamingState(layout)
