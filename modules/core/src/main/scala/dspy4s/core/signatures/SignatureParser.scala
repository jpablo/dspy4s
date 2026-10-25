package dspy4s.core.signatures

import dspy4s.core.collections.Traverse
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.ValidationError

import scala.util.matching.Regex

/** The parser of the string DSL of a signature, for example `"question, context -> answer: int"`. */
object SignatureParser:

  /** The name of a layout that the parser makes when the caller gives no name. */
  private[signatures] val DefaultName: String = "StringSignature"

  def parse(signatureDsl: String, name: String = DefaultName): Either[DspyError, SignatureLayout] =
    val trimmed = signatureDsl.trim
    if trimmed.isEmpty then Left(ValidationError("SignatureLayout DSL cannot be empty"))
    else
      val arrowCount = "->".r.findAllIn(trimmed).length
      if arrowCount != 1 then
        Left(ValidationError(s"Invalid signature format '$signatureDsl'. Must contain exactly one '->'."))
      else
        val Array(inputSegment, outputSegment) = trimmed.split("->", 2).map(_.trim)
        for
          inputs    <- parseSegment(inputSegment)
          outputs   <- parseSegment(outputSegment)
          signature <- SignatureLayout.create(name = name, inputFields = inputs, outputFields = outputs)
        yield signature

  private def parseSegment(segment: String): Either[DspyError, Vector[FieldSpec]] =
    if segment.isEmpty then Right(Vector.empty)
    else
      val tokens = splitOutsideBrackets(segment, ',').map(_.trim).filter(_.nonEmpty)
      Traverse.either(tokens)(parseField)

  private def parseField(token: String): Either[DspyError, FieldSpec] =
    val parts     = token.split(":", 2).map(_.trim)
    val fieldName = parts(0)
    if !FieldSpec.validateName(fieldName) then
      Left(ValidationError(s"Invalid field name '$fieldName' in signature token '$token'"))
    else
      val (typeRef, enumValues, optional) =
        if parts.length == 1 || parts(1).isEmpty then (TypeRef.string, Vector.empty, false)
        else typeOf(parts(1))
      Right(FieldSpec(name = fieldName, typeRef = typeRef, enumValues = enumValues, optional = optional))

  /** The type of a type token, its enum values, and `true` when the token permits no value (upstream
    * `annotation_allows_none`). `Optional[T]`, `Union[T, None]`, `Union[None, T]`, `T | None`, and `None | T` give the
    * type `T` and `true`.
    *
    * A segment of the signature splits into fields at each comma outside brackets, so a type can have commas, for
    * example `scores: dict[str, int]` or `note: Optional[dict[str, int]]`.
    */
  private def typeOf(token: String): (TypeRef, Vector[String], Boolean) =
    val members = bracketArgs(token, "Optional").map(inner => Vector(inner, NoneToken))
      .orElse(bracketArgs(token, "Union").map(splitOutsideBrackets(_, ',')))
      .getOrElse(splitOutsideBrackets(token, '|'))
      .map(_.trim)
    val others                = members.filterNot(_ == NoneToken)
    val optional              = others.nonEmpty && others.size < members.size
    val (typeRef, enumValues) = valueType(if optional then others.mkString(" | ") else token)
    (typeRef, enumValues, optional)

  /** The type and the enum values of a type token with no `None`. A `Literal` with string members, for example
    * `Literal['a', "b"]`, is a string type with the members as enum values: a Python string signature gives such a
    * field the `Literal` annotation, and the port keeps the members as the enum values of a string field. A `Literal`
    * with other members is an opaque type, as each other unknown token.
    */
  private def valueType(token: String): (TypeRef, Vector[String]) =
    stringLiteralMembers(token.trim).fold((TypeRef.fromToken(token), Vector.empty[String])) { members =>
      (TypeRef.string, members)
    }

  private val NoneToken: String = "None"

  /** One string member of a `Literal`: a text in single quotes or in double quotes, with no quote and no backslash in
    * it.
    */
  private val StringMember: String = """'([^'\\]*)'|"([^"\\]*)""""

  /** The members of a `Literal`: string members, separated by commas, with an optional comma at the end. */
  private val StringMembers: Regex = s"""\\s*(?:$StringMember)\\s*(?:,\\s*(?:$StringMember)\\s*)*,?\\s*""".r

  /** The members of `token` when it is `Literal[...]` with string members only. */
  private def stringLiteralMembers(token: String): Option[Vector[String]] =
    bracketArgs(token, "Literal").filter(StringMembers.matches).map { inner =>
      StringMember.r.findAllMatchIn(inner).map(found => Option(found.group(1)).getOrElse(found.group(2))).toVector
    }

  /** The parts of `text` between the `separator` characters that are outside brackets (`[]`, `()`, or `{}`). */
  private def splitOutsideBrackets(text: String, separator: Char): Vector[String] =
    val depths = bracketDepths(text)
    val cuts   = text.indices.filter(index => text(index) == separator && depths(index) == 0).toVector
    val bounds = (-1 +: cuts) :+ text.length
    bounds.zip(bounds.tail).map((from, to) => text.substring(from + 1, to))

  /** The text between the outer brackets of `token` when `token` is `name[...]`, for example `int, None` for
    * `Union[int, None]`. The brackets of the text must balance, so `Optional[int] | Optional[str]` has no such text.
    */
  private def bracketArgs(token: String, name: String): Option[String] =
    Option
      .when(token.startsWith(s"$name[") && token.endsWith("]"))(token.substring(name.length + 1, token.length - 1))
      .filter { inner =>
        val depths = bracketDepths(inner)
        depths.forall(_ >= 0) && depths.lastOption.contains(0)
      }

  /** The bracket depth before each character of `text`, and the depth after the last character. */
  private def bracketDepths(text: String): IndexedSeq[Int] =
    text.scanLeft(0) { (depth, char) =>
      char match
        case '[' | '(' | '{' => depth + 1
        case ']' | ')' | '}' => depth - 1
        case _               => depth
    }
