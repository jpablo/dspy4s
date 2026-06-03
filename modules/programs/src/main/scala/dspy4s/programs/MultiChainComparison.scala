package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ProgramCall
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** Compares multiple candidate reasoning chains for the same task and asks
  * an LM to produce a corrected reasoning + final answer.
  *
  * Port of Python DSPy's `dspy.MultiChainComparison`. The flow:
  *
  *   1. Take the user's `baseSignature` (e.g. `question -> answer`).
  *   2. Append `M` `reasoning_attempt_i` input fields to it.
  *   3. Prepend a `rationale` output field for the corrected reasoning.
  *   4. Render each candidate completion as
  *      `«I'm trying to <rationale>. I'm not sure but my prediction is <answer>»`
  *      and feed them as the new attempt inputs.
  *   5. Run the augmented `DynamicPredict` to obtain the corrected reasoning and
  *      final answer.
  *
  * @param baseSignature the original task signature
  * @param attempts the candidate completions to compare (must have length `M`)
  * @param m number of expected attempts (validated against `attempts.length`)
  * @param temperature temperature for the comparison call; matches Python's
  *                    default of 0.7
  */
final case class MultiChainComparison(
    baseSignature: SignatureLayout,
    m: Int = 3,
    temperature: Double = 0.7,
    rationaleFieldName: String = "rationale",
    rationalePrefix: String = "Accurate Reasoning: Thank you everyone. Let's now holistically",
    rationaleDescription: String = "${corrected reasoning}",
    attemptDescription: String = "${reasoning attempt}",
    answerFieldOverride: Option[String] = None
) extends Module:

  override val moduleName: String = "multi_chain_comparison"

  /** The output field used to render the "prediction" part of each attempt
    * line. Defaults to the last output field in `baseSignature`. */
  private val lastOutputName: Option[String] =
    answerFieldOverride.orElse(baseSignature.outputFields.lastOption.map(_.name))

  /** The augmented signature: `baseSignature` plus `m` attempt-input fields
    * appended, plus a `rationale` output field prepended (matches Python
    * field ordering). */
  val augmentedSignature: SignatureLayout =
    val withAttempts = (1 to m).foldLeft(baseSignature) { (sig, idx) =>
      sig.append(
        FieldSpec(
          name = s"reasoning_attempt_$idx",
          role = FieldRole.Input,
          description = Some(attemptDescription),
          prefix = Some(s"Student Attempt #$idx:")
        )
      )
    }
    withAttempts.prepend(
      FieldSpec(
        name = rationaleFieldName,
        role = FieldRole.Output,
        description = Some(rationaleDescription),
        prefix = Some(rationalePrefix)
      )
    )

  override protected def forward(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    // Candidate completions are program data, not provider config: they are supplied through the typed
    // `runWithAttempts` entry below. The generic `run` has none and surfaces the usual size error.
    runWithAttempts(input, Vector.empty)

  /** Public entry that accepts the candidate completions directly. Mirrors
    * Python's `compare_answers(completions, question=...)` shape — the
    * inputs go into `ProgramCall.inputs`, the candidates here. */
  def runWithAttempts(
      input: ProgramCall,
      attempts: Vector[Any]
  )(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    if attempts.size != m then
      Left(dspy4s.core.contracts.ValidationError(
        s"Number of attempts (${attempts.size}) doesn't match the configured m ($m). " +
          s"Pass exactly $m candidates."
      ))
    else
      val attemptLines = attempts.map(formatAttempt)
      val appended = attemptLines.zipWithIndex.map { case (line, idx) =>
        s"reasoning_attempt_${idx + 1}" -> (DynamicValue.Primitive(PrimitiveValue.String(line)): DynamicValue)
      }
      val augmentedInputs = DynamicValue.Record(Chunk.from(
        input.inputs.fields.iterator.toSeq ++ appended
      ))
      DynamicPredict(layout = augmentedSignature)
        .apply(input.copy(inputs = augmentedInputs))

  /** Renders a single attempt as Python does:
    * `«I'm trying to {rationale}. I'm not sure but my prediction is {answer}»`.
    * Accepts either a `DynamicPrediction` or a `DynamicValue.Record` row. */
  private def formatAttempt(attempt: Any): String =
    val rowOpt: Option[DynamicValue.Record] = attempt match
      case p: DynamicPrediction      => Some(p.values)
      case rec: DynamicValue.Record  => Some(rec)
      case _                         => None

    rowOpt match
      case Some(row) =>
        val rationale = firstNonEmpty(row, Seq("rationale", "reasoning"))
        val answer    = lastOutputName.flatMap(name => DynamicValues.recordGet(row, name))
          .map(DynamicValues.renderText).getOrElse("")
        s"«I'm trying to $rationale I'm not sure but my prediction is $answer»"
      case None =>
        s"«$attempt»"

  private def firstNonEmpty(row: DynamicValue.Record, keys: Seq[String]): String =
    keys.iterator
      .flatMap(k => DynamicValues.recordGet(row, k).map(DynamicValues.renderText))
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(_.linesIterator.next().trim)
      .nextOption()
      .getOrElse("")
