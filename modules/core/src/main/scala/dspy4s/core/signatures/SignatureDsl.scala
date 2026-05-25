package dspy4s.core.signatures

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.SignatureLayout

object SignatureDsl:
  private val parser = SignatureParser()

  def parse(dsl: String, name: String = "StringSignature"): Either[DspyError, SignatureLayout] =
    parser.parse(dsl, name)

  def create(
      name: String,
      inputFields: Vector[FieldSpec],
      outputFields: Vector[FieldSpec],
      instructions: Option[String] = None
  ): Either[DspyError, SignatureLayout] =
    val typedInputs = inputFields.map(_.copy(role = FieldRole.Input))
    val typedOutputs = outputFields.map(_.copy(role = FieldRole.Output))
    SignatureLayout.create(name, typedInputs ++ typedOutputs, instructions)

  def defaultInstructions(layout: SignatureLayout): String =
    val inputs = layout.inputFields.map(f => s"`${f.name}`").mkString(", ")
    val outputs = layout.outputFields.map(f => s"`${f.name}`").mkString(", ")
    s"Given the fields $inputs, produce the fields $outputs."
