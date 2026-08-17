package dspy4s.programs

import dspy4s.core.contracts.{DynamicValues, FieldSpec, TypeRef, ValidationError, :=}
import dspy4s.core.contracts.SignatureOps.*
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.signatures.OutputAugmentation.PrependField
import dspy4s.signatures.{InputAugmentation, OutputAugmentation, Signature}
import zio.blocks.schema.DynamicValue

/** Multi-chain comparison as input preparation followed by one prediction instruction. */
object MultiChainComparison:
  type RationaleName    = "rationale"
  type WithRationale[O] = OutputAugmentation.WithField[O, RationaleName, String]

  final case class Input[I](baseInput: I, attempts: Vector[RawPrediction])

  private inline val rationaleName: RationaleName = scala.compiletime.constValue[RationaleName]

  def apply[I, O](
      id                  : ParameterId,
      baseSignature       : Signature[I, O],
      m                   : Int                 = 3,
      temperature         : Double              = 0.7,
      rationalePrefix     : String              = "Accurate Reasoning: Thank you everyone. Let's now holistically",
      rationaleDescription: String              = "${corrected reasoning}",
      attemptDescription  : String              = "${reasoning attempt}",
      answerFieldOverride : Option[String]      = None,
      demos               : Vector[Example]     = Vector.empty,
      config              : DynamicValue.Record = DynamicValue.Record.empty,
      name                : String              = "multi_chain_comparison"
  )(using prepend: PrependField.Of[RationaleName, String, O]): Program[Input[I], WithRationale[O]] =
    require(m > 0, "MultiChainComparison m must be positive")

    val attemptFields = (1 to m).toVector.map { index =>
      FieldSpec.normalize(FieldSpec(
        name = s"reasoning_attempt_$index",
        typeRef = TypeRef.string,
        description = Some(attemptDescription),
        prefix = Some(s"Student Attempt #$index:")
      ))
    }
    val rationaleField = FieldSpec.normalize(FieldSpec(
      name = rationaleName,
      typeRef = TypeRef.string,
      description = Some(rationaleDescription),
      prefix = Some(rationalePrefix)
    ))
    val layout = attemptFields
      .foldLeft(baseSignature.layout)(_.appendInput(_))
      .prependOutput(rationaleField)
    val attemptInputs = InputAugmentation.appendedStringInputs(
      baseSignature.inputShape,
      attemptFields,
      "MultiChainComparison"
    )
    type Attempts = attemptInputs.Values

    val answerField = answerFieldOverride.orElse(baseSignature.layout.outputFields.lastOption.map(_.name))
    val prepare     = Program.liftEither[Input[I], (I, Attempts)] { input =>
      if input.attempts.size != m then
        Left(ValidationError(
          s"Number of attempts (${input.attempts.size}) does not match the configured m ($m). Pass exactly $m candidates."
        ))
      else
        attemptInputs
          .validate(input.attempts.map(formatAttempt(_, answerField)))
          .map(input.baseInput -> _)
    }
    val signature = Signature[(I, Attempts), WithRationale[O]](
      name = baseSignature.name,
      layout = layout,
      inputShape = attemptInputs.shape,
      outputShape = OutputAugmentation.prependedStringShape(
        baseSignature.outputShape,
        rationaleField,
        rationaleName,
        "MultiChainComparison",
        baseSignature.name
      )
    )
    val defaultConfig = DynamicValues.record("temperature" := temperature)
    val compare       = Program.predict(
      id,
      signature,
      demos,
      DynamicValues.mergeRecords(defaultConfig, config),
      name
    )

    prepare >>> compare

  private def formatAttempt(attempt: RawPrediction, answerField: Option[String]): String =
    val rationale = firstNonEmpty(attempt, Vector("rationale", "reasoning"))
    val answer    = answerField
      .flatMap(field => DynamicValues.recordGet(attempt.values, field))
      .map(DynamicValues.renderText)
      .getOrElse("")
    s"«I'm trying to $rationale I'm not sure but my prediction is $answer»"

  private def firstNonEmpty(attempt: RawPrediction, fields: Vector[String]): String =
    fields.iterator
      .flatMap(field => DynamicValues.recordGet(attempt.values, field).map(DynamicValues.renderText))
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(_.linesIterator.next().trim)
      .nextOption()
      .getOrElse("")
