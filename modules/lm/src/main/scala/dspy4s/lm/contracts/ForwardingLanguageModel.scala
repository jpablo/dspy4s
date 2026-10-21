package dspy4s.lm.contracts

import zio.blocks.schema.DynamicValue

/** A model that wraps another model, [[delegate]], and gives each member of [[LanguageModel]] that has a default from
  * the delegate: the capability flags, the default options, the cache identity, and `close`. A wrapper that extends
  * this trait thus does not hide what its delegate supports. Adapters read the flags to select native tools or
  * `response_format`, `Predict` reads `supportsDecisionRequests`, and the cache key reads the default options and the
  * cache identity.
  *
  * A wrapper defines `id`, `mode`, and `call`. It overrides a forwarded member only to change it, for example a `close`
  * that does not close a delegate that the wrapper does not own. The async calls (`acall`, `acallExecuted`) are not
  * forwarded: they run `call` of the wrapper, so the rules of the wrapper apply to them.
  */
trait ForwardingLanguageModel extends LanguageModel:

  /** The model that this wrapper wraps. */
  def delegate: LanguageModel

  override def supportsFunctionCalling: Boolean    = delegate.supportsFunctionCalling
  override def supportsResponseSchema: Boolean     = delegate.supportsResponseSchema
  override def supportsReasoning: Boolean          = delegate.supportsReasoning
  override def supportsDecisionRequests: Boolean   = delegate.supportsDecisionRequests
  override def defaultOptions: DynamicValue.Record = delegate.defaultOptions
  override def cacheIdentity: DynamicValue.Record  = delegate.cacheIdentity

  /** Close the delegate. */
  override def close(): Unit = delegate.close()
