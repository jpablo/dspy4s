package dspy4s.lm.runtime

import dspy4s.core.contracts.{DspyError, DynamicValues, ValidationError, recordSchema}
import dspy4s.lm.contracts.{CacheKey, DecisionPayload, LmMode, LmRequest, Message}
import zio.blocks.schema.{DynamicValue, Schema}

import scala.util.control.NonFatal

/** The cache key of a request (upstream `Cache.cache_key` in `dspy/clients/cache.py`). The key is the SHA-256 hash of
  * the JSON text of one record: the model, the mode, the messages with all their fields, the options, the rollout id,
  * the decision payload, and the cache identity of the model. The keys of each record and of each map in it are in
  * sorted order, so two requests that differ only in the order of their options get one key. The JSON text keeps the
  * type of each value, so a string `"1"` and a number `1` give different keys.
  */
object RequestHash:

  /** The data of a key. The `Schema` gives each field of a message, so a new field of [[Message]] goes into the key. */
  private final case class KeyData(
      model        : String,
      mode         : LmMode,
      messages     : Vector[Message],
      options      : DynamicValue.Record,
      rolloutId    : Option[Int],
      decision     : Option[DecisionPayload],
      cacheIdentity: DynamicValue.Record
  ) derives Schema

  /** The cache key of `request` for a model with the mode `mode` and the cache identity `cacheIdentity`, or a
    * `ValidationError` when the request has no key. A value with no JSON text gives no key: a number that is not
    * finite, a text with a lone UTF-16 surrogate, or a value that is nested too deep for the stack of the thread, as
    * the arguments of a tool call in a message can be (the rule of `DynamicValues.tryEncodeJson`). A cache stores and
    * reads nothing for such a request, so the model gets the call (upstream `Cache.get` and `Cache.put` catch the
    * exception of `cache_key`, log it, and go on with no cache).
    */
  def forRequest(
      request      : LmRequest,
      mode         : LmMode,
      cacheIdentity: DynamicValue.Record = DynamicValue.Record.empty
  ): Either[DspyError, CacheKey] =
    val data = KeyData(
      request.model,
      mode,
      request.messages,
      request.options,
      request.rolloutId,
      request.decision,
      cacheIdentity
    )
    try
      DynamicValues.tryEncodeJson(Schema[KeyData].toDynamicValue(data).sortFields.sortMapKeys) match
        case Right(json) => Right(CacheKey.of(json))
        case Left(cause) => Left(ValidationError(s"The request has no cache key: $cause"))
    catch
      case _: StackOverflowError => Left(ValidationError("The request has no cache key: a value is nested too deep"))
      case NonFatal(error)       => Left(ValidationError(s"The request has no cache key: ${DspyError.messageOf(error)}"))
