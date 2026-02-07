package dspy4s.core.signatures

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.Signature
import dspy4s.core.contracts.SignatureSpec

object SignatureDsl:
  private val parser = DefaultSignatureParser()

  def parse(dsl: String, name: String = "StringSignature"): Either[DspyError, Signature] =
    parser.parse(dsl, name)

  def create(
      name: String,
      inputFields: Vector[FieldSpec],
      outputFields: Vector[FieldSpec],
      instructions: Option[String] = None
  ): Either[DspyError, Signature] =
    val typedInputs = inputFields.map(_.copy(role = FieldRole.Input))
    val typedOutputs = outputFields.map(_.copy(role = FieldRole.Output))
    SignatureSpec.create(name, typedInputs ++ typedOutputs, instructions)

  def defaultInstructions(signature: Signature): String =
    val inputs = signature.inputFields.map(f => s"`${f.name}`").mkString(", ")
    val outputs = signature.outputFields.map(f => s"`${f.name}`").mkString(", ")
    s"Given the fields $inputs, produce the fields $outputs."
