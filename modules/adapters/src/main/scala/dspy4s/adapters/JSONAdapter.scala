package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterErrors
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.ValidationError
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import ujson.Value

import scala.util.Try

final case class JSONAdapter(
    name: String = "json",
    allowTextFallbackForSingleOutput: Boolean = true
) extends Adapter:
  override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    val fieldList = invocation.layout.outputFields.map(_.name).mkString(", ")
    val jsonInstruction =
      s"Return a valid JSON object with exactly these keys: $fieldList. Do not include markdown fences."
    val systemText = invocation.layout.instructions match
      case Some(instructions) => s"$instructions\n\n$jsonInstruction"
      case None               => jsonInstruction

    val demoMessages = invocation.demos.flatMap { demo =>
      val userText = renderFields(invocation.layout.inputFields, demo.values)
      val assistantJson = invocation.layout.outputFields
        .flatMap(field => demo.values.get(field.name).map(value => field.name -> value.toString))
        .toMap
      Vector(
        Message(role = MessageRole.User, text = Some(userText)),
        Message(role = MessageRole.Assistant, text = Some(ujson.write(assistantJson)))
      )
    }

    val inputMessage = Message(
      role = MessageRole.User,
      text = Some(renderFields(invocation.layout.inputFields, invocation.inputs.values))
    )

    Right(
      FormattedPrompt(
        messages = Vector(Message(role = MessageRole.System, text = Some(systemText))) ++ demoMessages ++ Vector(
          inputMessage
        )
      )
    )

  override def streamingState(layout: SignatureLayout): Option[AdapterStreamingState] =
    Some(new JsonStreamingState(layout.outputFields))

  override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
    parseStructured(layout, output).orElse {
      if allowTextFallbackForSingleOutput && layout.outputFields.size == 1 then
        val field = layout.outputFields.head
        val trimmed = output.text.trim
        if trimmed.nonEmpty then
          Right(
            ParsedOutput(
              values = Map(field.name -> trimmed),
              rawText = Some(output.text),
              metadata = Map("adapter" -> name, "fallback" -> "text")
            )
          )
        else Left(ParseError("adapter", "Cannot fallback from empty model output"))
      else Left(ParseError("adapter", "JSON parse failed and no fallback was applied"))
    }

  private def parseStructured(layout: SignatureLayout, output: LmOutput): Either[DspyError, ParsedOutput] =
    for
      jsonText <- extractJson(output.text)
      root <- parseJsonObject(jsonText)
      values <- layout.outputFields.foldLeft[Either[DspyError, Map[String, Any]]](Right(Map.empty)) { (acc, field) =>
        for
          soFar <- acc
          value <- root.obj.get(field.name) match
            case Some(raw) => coerce(field.typeRef, raw)
            case None      => Left(AdapterErrors.missingField(field.name))
        yield soFar.updated(field.name, value)
      }
    yield ParsedOutput(values = values, rawText = Some(output.text), metadata = Map("adapter" -> name))

  private def extractJson(text: String): Either[DspyError, String] =
    val trimmed = text.trim
    if trimmed.startsWith("{") && trimmed.endsWith("}") then Right(trimmed)
    else
      val fencedPattern = "(?s)```json\\s*(\\{.*?\\})\\s*```".r
      fencedPattern.findFirstMatchIn(text).map(_.group(1)) match
        case Some(json) => Right(json)
        case None =>
          extractFirstJsonObject(text).toRight(ParseError("adapter", "Could not find JSON object in model output"))

  private def extractFirstJsonObject(text: String): Option[String] =
    val start = text.indexOf('{')
    if start < 0 then None
    else
      var depth = 0
      var end = -1
      var i = start
      while i < text.length && end < 0 do
        text.charAt(i) match
          case '{' => depth += 1
          case '}' =>
            depth -= 1
            if depth == 0 then end = i
          case _ => ()
        i += 1
      if end >= start then Some(text.substring(start, end + 1))
      else None

  private def parseJsonObject(raw: String): Either[DspyError, Value] =
    Try(ujson.read(raw)).toEither.left.map(error => ParseError("adapter", error.getMessage)).flatMap { value =>
      if value.objOpt.isDefined then Right(value)
      else Left(ParseError("adapter", "Parsed JSON output is not an object"))
    }

  private def coerce(typeRef: TypeRef, value: Value): Either[DspyError, Any] =
    typeRef match
      case TypeRef.int =>
        value.numOpt.map(_.toInt).toRight(ValidationError(s"Expected integer value, found: $value"))
      case TypeRef.double =>
        value.numOpt.toRight(ValidationError(s"Expected numeric value, found: $value"))
      case TypeRef.bool =>
        value.boolOpt.toRight(ValidationError(s"Expected boolean value, found: $value"))
      case TypeRef.json =>
        Right(fromJson(value))
      case _ =>
        Right(value.strOpt.getOrElse(renderJson(value)))

  private def fromJson(value: Value): Any =
    value match
      case ujson.Str(v)  => v
      case ujson.Num(v)  => v
      case ujson.Bool(v) => v
      case ujson.Null    => null
      case obj: ujson.Obj =>
        obj.value.iterator.map { case (k, v) => k -> fromJson(v) }.toMap
      case arr: ujson.Arr =>
        arr.value.toVector.map(fromJson)

  private def renderJson(value: Value): String =
    value match
      case ujson.Str(v) => v
      case other        => other.render()

  private def renderFields(fields: Vector[dspy4s.core.contracts.FieldSpec], values: Map[String, Any]): String =
    fields.flatMap { field =>
      val rendered = values.get(field.name).orElse(field.defaultValue).map(_.toString)
      rendered.map { value =>
        val prefix = field.prefix.getOrElse(s"${field.name}:")
        s"$prefix $value"
      }
    }.mkString("\n")
