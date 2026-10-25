package dspy4s.core.signatures

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.SignatureLayout

object SignatureDsl:

  def parse(dsl: String, name: String = SignatureParser.DefaultName): Either[DspyError, SignatureLayout] =
    SignatureParser.parse(dsl, name)

  def defaultInstructions(layout: SignatureLayout): String =
    val inputs  = layout.inputFields.map(f => s"`${f.name}`").mkString(", ")
    val outputs = layout.outputFields.map(f => s"`${f.name}`").mkString(", ")
    s"Given the fields $inputs, produce the fields $outputs."
