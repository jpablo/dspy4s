package dspy4s.core.signatures

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.ValidationError

final class SignatureParser:
  def parse(signatureDsl: String, name: String = "StringSignature"): Either[DspyError, SignatureLayout] =
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
      val tokens = SignatureParser.splitOutsideBrackets(segment, ',').map(_.trim).filter(_.nonEmpty)
      tokens.foldLeft[Either[DspyError, Vector[FieldSpec]]](Right(Vector.empty)) { (acc, token) =>
        for
          fields <- acc
          field  <- parseField(token)
        yield fields :+ field
      }

  private def parseField(token: String): Either[DspyError, FieldSpec] =
    val parts     = token.split(":", 2).map(_.trim)
    val fieldName = parts(0)
    if !FieldSpec.validateName(fieldName) then
      Left(ValidationError(s"Invalid field name '$fieldName' in signature token '$token'"))
    else
      val (typeRef, optional) =
        if parts.length == 1 || parts(1).isEmpty then (TypeRef.string, false)
        else typeOf(parts(1))
      Right(FieldSpec(name = fieldName, typeRef = typeRef, optional = optional))

  /** The type of a type token, and `true` when the token permits no value (upstream `annotation_allows_none`).
    * `Optional[T]`, `Union[T, None]`, `Union[None, T]`, `T | None`, and `None | T` give the type `T` and `true`.
    *
    * A segment of the signature splits into fields at each comma outside brackets, so a type can have commas, for
    * example `scores: dict[str, int]` or `note: Optional[dict[str, int]]`.
    */
  private def typeOf(token: String): (TypeRef, Boolean) =
    import SignatureParser.{NoneToken, bracketArgs, splitOutsideBrackets}
    val members = bracketArgs(token, "Optional").map(inner => Vector(inner, NoneToken))
      .orElse(bracketArgs(token, "Union").map(splitOutsideBrackets(_, ',')))
      .getOrElse(splitOutsideBrackets(token, '|'))
      .map(_.trim)
    val others = members.filterNot(_ == NoneToken)
    if others.nonEmpty && others.size < members.size then (TypeRef.fromToken(others.mkString(" | ")), true)
    else (TypeRef.fromToken(token), false)

object SignatureParser:
  private val NoneToken: String = "None"

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
