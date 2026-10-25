package dspy4s.lm.providers

import dspy4s.core.contracts.{DspyError, DynamicValues, ParseError}
import zio.blocks.schema.DynamicValue

/** The JSON rules of the provider layer. The option bag of a provider body has no null field, because a provider can
  * read an absent option and a null option in different ways ([[encode]]). A body from the network can be any text
  * ([[decode]]). The generic JSON helpers are in [[dspy4s.core.contracts.DynamicValues]].
  */
private[lm] object DynamicJson:

  /** The JSON text of a provider body. Each field of the top-level record with a null value is removed first: these
    * fields are the option bag, and a null option means "no option". A nested null stays, because it has a meaning on
    * the wire. For example, an assistant message with tool calls and no text has `"content": null`, and a JSON schema
    * can have `"default": null`.
    */
  def encode(value: DynamicValue): String =
    value match
      case rec: DynamicValue.Record => DynamicValues.encodeJson(withoutNullFields(rec))
      case other                    => DynamicValues.encodeJson(other)

  /** `rec` with no field that has a null value. Only the fields of `rec` change: a nested null stays. This is the rule
    * of [[encode]]. A wire can use it to give the record that [[encode]] sends.
    */
  def withoutNullFields(rec: DynamicValue.Record): DynamicValue.Record =
    if rec.fields.exists((_, field) => DynamicValues.isNull(field)) then
      DynamicValue.Record(rec.fields.filterNot((_, field) => DynamicValues.isNull(field)))
    else rec

  /** Parse a JSON string. Text that is not JSON gives a `ParseError` with the cause, and so does a value that is nested
    * too deep for the stack of the thread ([[dspy4s.core.contracts.DynamicValues.tryDecodeJson]]). A caller that needs
    * no cause uses `DynamicValues.decodeJson`.
    */
  def decode(json: String): Either[DspyError, DynamicValue] =
    DynamicValues.tryDecodeJson(json).left.map(cause => ParseError("json", s"Invalid JSON: ${cause.take(200)}"))
