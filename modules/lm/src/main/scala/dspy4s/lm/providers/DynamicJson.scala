package dspy4s.lm.providers

import dspy4s.core.contracts.{DspyError, DynamicValues, ParseError}
import zio.blocks.schema.{DynamicValue, Schema}

import java.nio.charset.StandardCharsets

/** The JSON rules of the provider layer. The option bag of a provider body has no null field, because a provider can
  * read an absent option and a null option in different ways ([[encode]]). A body from the network can be any text
  * ([[decode]]). The generic JSON helpers are in [[dspy4s.core.contracts.DynamicValues]].
  */
private[lm] object DynamicJson:
  private val codec = Schema.dynamic.jsonCodec

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

  /** Parse a JSON string. Text that is not JSON gives a `ParseError`. The codec is recursive, so a value that is nested
    * too deep for the stack of the thread gives a `ParseError` too. Thus a body from the network cannot stop the caller
    * with a `StackOverflowError`.
    */
  def decode(json: String): Either[DspyError, DynamicValue] =
    try
      codec.decode(json.getBytes(StandardCharsets.UTF_8)) match
        case Right(dv) => Right(dv)
        case Left(err) => Left(ParseError("json", s"Invalid JSON: ${err.toString.take(200)}"))
    catch case _: StackOverflowError => Left(ParseError("json", "Invalid JSON: the value is nested too deep"))
