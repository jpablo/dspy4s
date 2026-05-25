package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureSchema
import dspy4s.core.contracts.TypeRef
import dspy4s.programs.contracts.PredictProgram
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ProgramRuntime
import dspy4s.programs.runtime.SettingsProgramRuntime

final case class ChainOfThought(
    baseSignature: SignatureSchema,
    demos: Vector[Example] = Vector.empty,
    reasoningFieldName: String = "reasoning",
    reasoningType: TypeRef = TypeRef.string,
    reasoningDescription: Option[String] = Some("${reasoning}"),
    reasoningPrefix: Option[String] = Some("Reasoning:"),
    runtime: ProgramRuntime = new SettingsProgramRuntime {}
) extends PredictProgram:

  private val reasoningField = FieldSpec(
    name = reasoningFieldName,
    role = FieldRole.Output,
    typeRef = reasoningType,
    description = reasoningDescription,
    prefix = reasoningPrefix
  )

  val signature: Either[DspyError, SignatureSchema] =
    if baseSignature.outputFields.exists(_.name == reasoningFieldName) then Right(baseSignature)
    else baseSignature.insert(index = 0, field = reasoningField)

  override val moduleName: String = "chain_of_thought"

  override def run(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    for
      augmented <- signature
      prediction <- Predict(signature = augmented, demos = demos, runtime = runtime).run(input)
    yield prediction
