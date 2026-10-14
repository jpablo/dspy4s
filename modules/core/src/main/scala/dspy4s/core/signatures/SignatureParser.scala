package dspy4s.core.signatures

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.ValidationError

import scala.util.matching.Regex

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
      val tokens = segment.split(",").map(_.trim).filter(_.nonEmpty).toVector
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
    * `Optional[T]`, `T | None`, and `None | T` give the type `T` and `true`.
    */
  private def typeOf(token: String): (TypeRef, Boolean) =
    token match
      case SignatureParser.OptionalToken(inner) => (TypeRef.fromToken(inner), true)
      case _                                    =>
        val members = token.split('|').map(_.trim).toVector
        val others  = members.filterNot(_ == SignatureParser.NoneToken)
        if others.nonEmpty && others.size < members.size then (TypeRef.fromToken(others.mkString(" | ")), true)
        else (TypeRef.fromToken(token), false)

object SignatureParser:
  private val OptionalToken: Regex = """Optional\[(.+)\]""".r
  private val NoneToken: String    = "None"
