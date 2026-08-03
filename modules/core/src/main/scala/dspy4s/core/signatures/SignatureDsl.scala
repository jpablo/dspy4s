package dspy4s.core.signatures

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.SignatureLayout

object SignatureDsl:
  private val parser = SignatureParser()

  def parse(dsl: String, name: String = "StringSignature"): Either[DspyError, SignatureLayout] =
    parser.parse(dsl, name)

  def defaultInstructions(layout: SignatureLayout): String =
    val inputs = layout.inputFields.map(f => s"`${f.name}`").mkString(", ")
    val outputs = layout.outputFields.map(f => s"`${f.name}`").mkString(", ")
    s"Given the fields $inputs, produce the fields $outputs."
