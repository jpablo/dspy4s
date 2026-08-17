package dspy4s.programs

import dspy4s.core.contracts.{FieldSpec, TypeRef}
import dspy4s.core.contracts.SignatureOps.*
import dspy4s.core.data.Example
import dspy4s.signatures.OutputAugmentation.PrependField
import dspy4s.signatures.{OutputAugmentation, Signature}
import zio.blocks.schema.DynamicValue

/** Chain-of-thought as a pure signature transformation and one prediction instruction. */
object ChainOfThought:
  type ReasoningName    = "reasoning"
  type WithReasoning[O] = OutputAugmentation.WithField[O, ReasoningName, String]

  private inline val reasoningName: ReasoningName = scala.compiletime.constValue[ReasoningName]
  private val reasoningField                      = FieldSpec.normalize(FieldSpec(
    name = reasoningName,
    typeRef = TypeRef.string,
    description = Some("${reasoning}")
  ))

  def apply[I, O](
      id       : ParameterId,
      signature: Signature[I, O],
      demos    : Vector[Example]     = Vector.empty,
      config   : DynamicValue.Record = DynamicValue.Record.empty,
      name     : String              = "chain_of_thought"
  )(using prepend: PrependField.Of[ReasoningName, String, O]): Program[I, WithReasoning[O]] =
    val augmented = Signature[I, WithReasoning[O]](
      name = signature.name,
      layout = signature.layout.prependOutput(reasoningField),
      inputShape = signature.inputShape,
      outputShape = OutputAugmentation.prependedStringShape(
        signature.outputShape,
        reasoningField,
        reasoningName,
        "ChainOfThought",
        signature.name
      )
    )
    Program.predict(id, augmented, demos, config, name)
