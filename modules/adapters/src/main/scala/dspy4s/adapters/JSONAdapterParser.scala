package dspy4s.adapters

import dspy4s.adapters.contracts.{NativeFunctionCalling, ParsedOutput}
import dspy4s.adapters.internal.{AdapterTextSupport, JsonTextScan}
import dspy4s.core.contracts.{
  DspyError,
  DynamicValues,
  FieldSpec,
  LenientValues,
  ParseError,
  PythonText,
  SignatureLayout,
  TypeRef,
  ValidationError
}
import dspy4s.lm.contracts.LmOutput
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** Decodes JSONAdapter's JSON body and provider-native tool calls into schema-backed `DynamicValue` fields. */
private[adapters] object JSONAdapterParser:

  /** The values of the output fields of `layout`, with the tool calls rule of [[AdapterTextSupport.parseOutputs]].
    *
    * @param nativeCalls
    *   `true` when native function calling is active for the call. The provider tool calls then fill the tool calls
    *   field, and an empty sequence when there are none. Else the field is an ordinary JSON key, also when the response
    *   has provider tool calls and the layout has no tool calls field.
    */
  def parse(
      allowTextFallbackForSingleOutput: Boolean,
      layout                          : SignatureLayout,
      output                          : LmOutput,
      nativeCalls                     : Boolean
  ): Either[DspyError, ParsedOutput] =
    AdapterTextSupport.parseOutputs(layout, output, nativeCalls)(parseText(allowTextFallbackForSingleOutput, _, output))

  /** The values of the text output fields `fields` from the JSON object of the text of `output` ([[extractJson]]). A
    * text with no JSON object gives the single-output text fallback ([[AdapterTextSupport.singleOutputTextFallback]])
    * when `allowTextFallbackForSingleOutput` is on and there is one text field. Else it is a parse error.
    */
  private def parseText(
      allowTextFallbackForSingleOutput: Boolean,
      fields                          : Vector[FieldSpec],
      output                          : LmOutput
  ): Either[DspyError, DynamicValue.Record] =
    extractJson(output.text) match
      case Right(root) => AdapterTextSupport.decodeOutputFields(fields, output.text) { field =>
          DynamicValues.recordGet(root, field.name) match
            case Some(raw) => decode(field, raw).map(Some(_))
            case None      => Right(None)
        }
      case Left(_) =>
        if allowTextFallbackForSingleOutput && fields.size == 1 then
          AdapterTextSupport.singleOutputTextFallback(fields.head, output)
        else Left(ParseError("adapter", "JSON parse failed and no fallback was applied", raw = Some(output.text)))

  /** The value of the output field `field` (upstream `parse_value`). A JSON null of an optional field is a null. A tool
    * calls field takes a JSON list of calls, or the JSON text of such a list. A JSON string for a field whose type is
    * not a string goes through the text coercion of the chat format (`AdapterTextSupport.outputTextValue`), as upstream
    * `parse_value` reads a string with `json_repair`: `"3"` for an `int` field is `3`, and `"[1, 2]"` for a `list`
    * field is a list. A JSON value for a `list[T]` field of a string signature must be a list whose items fit `T`, as
    * in the chat format (`AdapterTextSupport.listOfValue`).
    *
    * A field with allowed values takes the rule of `AdapterTextSupport.allowedJsonValue` (decision D7): a JSON string
    * only. Each other JSON value is an error: upstream `parse_value` validates a value that is not a string against the
    * `Literal`, whose members are strings.
    */
  private def decode(field: FieldSpec, value: DynamicValue): Either[DspyError, DynamicValue] =
    if AdapterTextSupport.hasAllowedValues(field) then AdapterTextSupport.allowedJsonValue(field, value)
    else
      value match
        case _: DynamicValue.Null.type if field.optional            => Right(DynamicValue.Null)
        case DynamicValue.Primitive(PrimitiveValue.String(text))    => AdapterTextSupport.outputTextValue(field, text)
        case other if NativeFunctionCalling.isToolCallsField(field) => AdapterTextSupport.toolCallsValue(other)
        case other                                                  => coerce(field.typeRef, other)

  /** The JSON object of `text`: the whole text when it is one JSON object, else the first JSON object of the text
    * ([[JsonTextScan.firstObject]]). Upstream `JSONAdapter.parse` reads the whole text with `json_repair`, and else the
    * first balanced object of a regex. The JSON stream state starts at the first `{` too, so the parser and the stream
    * read the same object. A fenced ```json block needs no rule of its own: when the only object is in the fence, the
    * first object is in the fence.
    */
  private def extractJson(text: String): Either[DspyError, DynamicValue.Record] =
    parseJsonObject(PythonText.strip(text)).orElse(
      JsonTextScan.firstObject(text)
        .toRight(ParseError("adapter", "Could not find JSON object in model output"))
        .flatMap(parseJsonObject)
    )

  /** The JSON object of `raw`, read with the rules of Python `json.loads` ([[PythonText.parseJson]]). */
  private def parseJsonObject(raw: String): Either[DspyError, DynamicValue.Record] =
    PythonText.parseJson(raw).flatMap {
      case record: DynamicValue.Record => Right(record)
      case _                           => Left(ParseError("adapter", "Parsed JSON output is not an object"))
    }

  /** The value of an output field of the type `typeRef` from a JSON value `value` that is not a string or a null. An
    * `int`, a `double`, and a `bool` field take the rule of `LenientValues.valueOf`, as a text does: a whole number,
    * also a whole float, keeps its exact value as an `Int`, a `Long`, or a `BigInt`, and the number 1 or 0 is a bool. A
    * `json` field, a `list` field, a `union` field, and an evidence field of the decision adapter
    * ([[DecisionAdapter.isEvidenceType]]) keep the value as it is. A field of another type takes the JSON text of the
    * value as its text. The parser encodes the JSON text one time, only when a rule reads it.
    */
  private def coerce(typeRef: TypeRef, value: DynamicValue): Either[DspyError, DynamicValue] =
    lazy val text                 = jsonText(value)
    def lenient(expected: String) =
      LenientValues.valueOf(value, typeRef).toRight(ValidationError(s"Expected $expected value, found: $text"))
    typeRef match
      case TypeRef.int                                          => lenient("integer")
      case TypeRef.double                                       => lenient("numeric")
      case TypeRef.bool                                         => lenient("boolean")
      case TypeRef.json | TypeRef.list | TypeRef.union          => Right(value)
      case evidence if DecisionAdapter.isEvidenceType(evidence) => Right(value)
      // A string signature type such as `list[str]`: the rule of the chat format (`AdapterTextSupport.listOfValue`).
      case other if AdapterTextSupport.isListOf(other) => AdapterTextSupport.listOfValue(other, Some(value), text)
      case _                                           => Right(DynamicValues.string(text))

  /** The text of a JSON value: a string is its text, and each other value is its JSON text ([[PythonText.json]]). */
  private def jsonText(value: DynamicValue): String =
    DynamicValues.asString(value).getOrElse(PythonText.json(value))
