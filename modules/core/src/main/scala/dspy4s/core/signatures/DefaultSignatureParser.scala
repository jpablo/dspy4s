package dspy4s.core.signatures

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.SignatureParser
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.ValidationError

final class DefaultSignatureParser extends SignatureParser:
  override def parse(signatureDsl: String, name: String = "StringSignature"): Either[DspyError, SignatureLayout] =
    val trimmed = signatureDsl.trim
    if trimmed.isEmpty then Left(ValidationError("SignatureLayout DSL cannot be empty"))
    else
      val arrowCount = "->".r.findAllIn(trimmed).length
      if arrowCount != 1 then
        Left(ValidationError(s"Invalid signature format '$signatureDsl'. Must contain exactly one '->'."))
      else
        val Array(inputSegment, outputSegment) = trimmed.split("->", 2).map(_.trim)
        for
          inputs <- parseSegment(inputSegment, FieldRole.Input)
          outputs <- parseSegment(outputSegment, FieldRole.Output)
          signature <- SignatureLayout.create(name = name, fields = inputs ++ outputs)
        yield signature

  private def parseSegment(segment: String, role: FieldRole): Either[DspyError, Vector[FieldSpec]] =
    if segment.isEmpty then Right(Vector.empty)
    else
      val tokens = segment.split(",").map(_.trim).filter(_.nonEmpty).toVector
      tokens.foldLeft[Either[DspyError, Vector[FieldSpec]]](Right(Vector.empty)) { (acc, token) =>
        for
          fields <- acc
          field <- parseField(token, role)
        yield fields :+ field
      }

  private def parseField(token: String, role: FieldRole): Either[DspyError, FieldSpec] =
    val parts = token.split(":", 2).map(_.trim)
    val fieldName = parts(0)
    if !FieldSpec.validateName(fieldName) then
      Left(ValidationError(s"Invalid field name '$fieldName' in signature token '$token'"))
    else
      val typeRef =
        if parts.length == 1 || parts(1).isEmpty then TypeRef.string
        else TypeRef.fromToken(parts(1))
      Right(FieldSpec(name = fieldName, role = role, typeRef = typeRef))
