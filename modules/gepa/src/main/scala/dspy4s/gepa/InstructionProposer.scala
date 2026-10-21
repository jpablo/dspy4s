package dspy4s.gepa

import dspy4s.adapters.JSONAdapter
import dspy4s.adapters.contracts.Adapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ValidationError
import dspy4s.core.contracts.:=
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ProgramRuntime
import dspy4s.programs.optimization.OptimizableId
import dspy4s.programs.runtime.SettingsProgramRuntime
import dspy4s.programs.strategies.DynamicPredict
import zio.blocks.schema.DynamicValue

/** The reflective mutation operator of GEPA: it writes new instruction text for components of a candidate (gepa's
  * `ProposalFn`). [[Gepa]] and [[GepaEngine]] use [[InstructionProposer.default]] when the caller gives no proposer.
  *
  * The engine calls [[propose]] one time for an iteration, in a context whose language model is the reflection LM
  * (dspy's `dspy.context(lm=reflection_lm)`). A proposer that runs a predictor thus needs no reference to that model.
  * The trace entries of that call do not reach the context of the GEPA caller. The history entries do.
  */
trait InstructionProposer:

  /** The new instruction text of components of `candidate`.
    *
    * @param candidate
    *   the parent candidate. It gives the current instruction of each component.
    * @param reflective
    *   the reflective records of each component that has one record at least
    * @param components
    *   the components that the engine selected for this iteration
    * @return
    *   the new text of each component. A component that `candidate` or `reflective` does not hold has no entry. An
    *   empty map gives no proposal. A `Left` is a proposal that failed: the engine calls [[propose]] one more time, and
    *   a second `Left` gives no proposal for the iteration. The engine changes a non-fatal exception into a `Left`
    *   (gepa catches each exception of the reflection). A fatal error goes up to the caller of the engine. A text for a
    *   component that `candidate` does not hold is an error of the proposer: the engine stops the run with a `Left`.
    */
  def propose(
      candidate : Candidate,
      reflective: Map[OptimizableId, Vector[ReflectiveRecord]],
      components: Vector[OptimizableId]
  )(using RuntimeContext): Either[DspyError, Map[OptimizableId, String]]

object InstructionProposer:

  /** The built-in proposer with no option (dspy's `InstructionProposer()`). */
  val default: InstructionProposer = DefaultInstructionProposer()

/** The default instruction proposer of GEPA (dspy's `InstructionProposer`, upstream b6c8aaf1e). For each component it
  * gives the current instruction and the reflective records to the reflection LM, through a predictor and `adapter`,
  * and it reads the new instruction from the output field `new_instruction`.
  *
  * Rules, as upstream:
  *   - The components are handled in the order of `components`. The first failure stops the call and is the result.
  *   - The records go to the model as one JSON text ([[ReflectiveRecord.toJson]]), in the input field
  *     `examples_with_feedback`.
  *   - The current instruction of a component is the text of the candidate. A component with no instruction gives the
  *     empty text.
  *   - The new instruction has no white space at its two ends. A new instruction that is empty is a failure
  *     ([[dspy4s.core.contracts.ValidationError]]).
  *   - The proposer never extracts text from a fence, and it never truncates an instruction.
  *
  * The length limit (`maxChars`):
  *   - The length is the number of Unicode code points of the instruction, with no white space at its two ends.
  *   - An instruction that is in the limit needs no second call.
  *   - An instruction that is too long gets one call that asks the model to shorten it, with the same adapter. The
  *     result has the same rules: not empty, and in the limit. A result that breaks a rule is a failure
  *     ([[dspy4s.core.contracts.ValidationError]]).
  *
  * The language model is the model of the context. The adapter is `adapter`, not the adapter of the context.
  *
  * Not ported: the upstream options `skills` and `truncate_history_outputs`.
  *
  * @param additionalInstructions
  *   more requirements for each new instruction, for example "Write instructions in imperative voice.". The text is
  *   trimmed. An empty text is the same as `None`.
  * @param baseInstructions
  *   replaces the instructions of the proposal call ([[DefaultInstructionProposer.ProposeInstructions]]). The fields
  *   stay. The text is trimmed. An empty text is the same as `None`.
  * @param maxChars
  *   the largest number of Unicode code points of a new instruction. `None` gives no limit.
  * @param adapter
  *   the adapter for the calls of the proposer. The default is a [[JSONAdapter]] that rejects a response that is not a
  *   JSON object, as the upstream JSON adapter does.
  */
final case class DefaultInstructionProposer(
    additionalInstructions: Option[String]               = None,
    baseInstructions      : Option[String]               = None,
    maxChars              : Option[InstructionCharLimit] = None,
    adapter               : Adapter                      = DefaultInstructionProposer.DefaultAdapter
) extends InstructionProposer:

  import DefaultInstructionProposer.*

  /** `additionalInstructions` with no white space at its two ends. `None` for an empty text. */
  private val additional: Option[String] = clean(additionalInstructions)

  /** The value of the input field `length_limit`. `None` without `maxChars`. */
  private val lengthLimit: Option[String] = maxChars.map(lengthLimitText)

  /** The signature of the proposal call (dspy's `ProposeInstruction`, with the optional fields of this proposer).
    *
    * The input fields, in this order: `additional_instructions` (only with `additionalInstructions`), `length_limit`
    * (only with `maxChars`), `current_instruction`, `examples_with_feedback`. The output field is `new_instruction`.
    * Each field has the description of the companion object. The instructions are `baseInstructions`, or
    * [[DefaultInstructionProposer.ProposeInstructions]].
    */
  val proposeSignature: SignatureLayout =
    val optional = Vector(
      additional.map(_ =>
        FieldSpec(AdditionalInstructionsField, description = Some(AdditionalInstructionsDescription))
      ),
      lengthLimit.map(_ => FieldSpec(LengthLimitField, description = Some(LengthLimitDescription)))
    ).flatten
    SignatureLayout.of(
      name = "ProposeInstruction",
      inputFields = optional ++ Vector(
        FieldSpec(CurrentInstructionField, description = Some(CurrentInstructionDescription)),
        FieldSpec(ExamplesWithFeedbackField, description = Some(ExamplesWithFeedbackDescription))
      ),
      outputFields = Vector(FieldSpec(NewInstructionField, description = Some(NewInstructionDescription))),
      instructions = Some(clean(baseInstructions).getOrElse(ProposeInstructions))
    )

  /** The signature of the call that shortens an instruction (dspy's `CompressInstruction`). `None` without `maxChars`.
    *
    * The input fields are `instruction` and `length_limit`. The output field is `shortened_instruction`. The fields
    * have no description. The instructions are [[DefaultInstructionProposer.CompressInstructions]].
    */
  val compressSignature: Option[SignatureLayout] = lengthLimit.map { _ =>
    SignatureLayout.of(
      name = "CompressInstruction",
      inputFields = Vector(FieldSpec(InstructionField), FieldSpec(LengthLimitField)),
      outputFields = Vector(FieldSpec(ShortenedInstructionField)),
      instructions = Some(CompressInstructions)
    )
  }

  /** The runtime of the two predictors: the language model of the context, and the adapter of this proposer. The
    * adapter of the context has no effect, and no setting of the context changes.
    */
  private val runtime: ProgramRuntime = new SettingsProgramRuntime:
    override def resolveAdapter(using RuntimeContext): Either[DspyError, Adapter] = Right(adapter)

  private val proposePredict: DynamicPredict =
    DynamicPredict(layout = proposeSignature, name = Some("gepa_propose_instruction"), runtime = runtime)

  private val compressPredict: Option[DynamicPredict] = compressSignature.map(layout =>
    DynamicPredict(layout = layout, name = Some("gepa_compress_instruction"), runtime = runtime)
  )

  override def propose(
      candidate : Candidate,
      reflective: Map[OptimizableId, Vector[ReflectiveRecord]],
      components: Vector[OptimizableId]
  )(using RuntimeContext): Either[DspyError, Map[OptimizableId, String]] =
    components.foldLeft[Either[DspyError, Map[OptimizableId, String]]](Right(Map.empty)) { (done, component) =>
      done.flatMap { texts =>
        // A component that the candidate or the reflective dataset does not hold gets no call and no entry.
        candidate.get(component).zip(reflective.get(component)) match
          case Some((current, records)) =>
            proposeFor(component, current.getOrElse(""), records).map(texts.updated(component, _))
          case None => Right(texts)
      }
    }

  /** The new instruction of one component: one proposal call, and then one call that shortens the draft when the draft
    * is longer than `maxChars`.
    */
  private def proposeFor(
      component: OptimizableId,
      current  : String,
      records  : Vector[ReflectiveRecord]
  )(using RuntimeContext): Either[DspyError, String] =
    val inputs = DynamicValues.recordFromEntries(
      additional.map(AdditionalInstructionsField := _).toVector ++
        lengthLimit.map(LengthLimitField := _).toVector ++
        Vector(CurrentInstructionField := current, ExamplesWithFeedbackField := ReflectiveRecord.toJson(records))
    )
    run(proposePredict, inputs, NewInstructionField).flatMap { draft =>
      if draft.isEmpty then Left(emptyInstruction(component)) else enforceLength(component, draft)
    }

  /** `draft` when it is in the limit. Else the result of one call that shortens `draft`: a `Left` when that result is
    * empty or is not in the limit. `draft` is not empty and has no white space at its two ends.
    */
  private def enforceLength(component: OptimizableId, draft: String)(using RuntimeContext): Either[DspyError, String] =
    maxChars.zip(compressPredict) match
      case Some((limit, compress)) if codePoints(draft) > limit =>
        val inputs = DynamicValues.record(InstructionField := draft, LengthLimitField := lengthLimitText(limit))
        run(compress, inputs, ShortenedInstructionField).flatMap { shortened =>
          val length = codePoints(shortened)
          if shortened.isEmpty then Left(emptyInstruction(component))
          else if length > limit then
            Left(
              ValidationError(
                s"The reflection LM returned an instruction of $length characters for component " +
                  s"'${component.render}', after one call to shorten it. The limit is $limit characters."
              )
            )
          else Right(shortened)
        }
      case _ => Right(draft)

  private def emptyInstruction(component: OptimizableId): DspyError =
    ValidationError(s"The reflection LM returned an empty instruction for component '${component.render}'.")

  /** Run `predict` on `inputs`. The result is the text of the output field `output`, with no white space at its two
    * ends.
    */
  private def run(predict: DynamicPredict, inputs: DynamicValue.Record, output: String)(using
      RuntimeContext
  ): Either[DspyError, String] =
    predict(ProgramCall(input = inputs)).map { prediction =>
      DynamicValues.recordGet(prediction.output, output).map(DynamicValues.renderText).getOrElse("").strip
    }

object DefaultInstructionProposer:

  /** The adapter of a proposer with no `adapter` option. Upstream uses a `JSONAdapter`, and that adapter gives an error
    * for a response that is not a JSON object. The fallback to plain text is thus off.
    */
  val DefaultAdapter: Adapter = JSONAdapter(allowTextFallbackForSingleOutput = false)

  /** The instructions of the proposal call (the docstring of dspy's `ProposeInstruction`). */
  val ProposeInstructions: String =
    "I provided an assistant with instructions to perform a task for me. You are given those instructions, along " +
      "with examples of different task inputs provided to the assistant, the assistant's response for each of them, " +
      "and some feedback on how the assistant's response could be better.\n\n" +
      "Your task is to write a new instruction for the assistant.\n\n" +
      "Read the inputs carefully and identify the input format and infer detailed task description about the task I " +
      "wish to solve with the assistant.\n\n" +
      "Read all the assistant responses and the corresponding feedback. Identify all niche and domain specific " +
      "factual information about the task and include it in the instruction, as a lot of it may not be available to " +
      "the assistant in the future. The assistant may have utilized a generalizable strategy to solve the task, if " +
      "so, include that in the instruction as well."

  /** The instructions of the call that shortens an instruction (the docstring of dspy's `CompressInstruction`). */
  val CompressInstructions: String = "Shorten the instruction to fit within the stated limit. Preserve every\n" +
    "strategy, rule, and constraint; cut redundancy and verbosity. Do not add\n" +
    "new content. Output only the shortened instruction."

  val CurrentInstructionDescription: String = "The instructions I provided to the assistant."

  val ExamplesWithFeedbackDescription: String =
    "Task inputs provided to the assistant, the assistant's response for each, and feedback on how the response " +
      "could be better."

  val NewInstructionDescription: String = "The new instruction for the assistant."

  val AdditionalInstructionsDescription: String =
    "Additional requirements from the user for the new instruction. Follow them."

  val LengthLimitDescription: String = "Hard limit on the length of the new instruction."

  /** The value of the input field `length_limit`. */
  def lengthLimitText(maxChars: InstructionCharLimit): String =
    s"The new instruction must be at most $maxChars characters."

  // The field names of the two signatures, as upstream.
  private val AdditionalInstructionsField = "additional_instructions"
  private val LengthLimitField            = "length_limit"
  private val CurrentInstructionField     = "current_instruction"
  private val ExamplesWithFeedbackField   = "examples_with_feedback"
  private val NewInstructionField         = "new_instruction"
  private val InstructionField            = "instruction"
  private val ShortenedInstructionField   = "shortened_instruction"

  /** `text` with no white space at its two ends. `None` for no text and for an empty text (dspy's `_clean_text`). */
  private def clean(text: Option[String]): Option[String] = text.map(_.strip).filter(_.nonEmpty)

  /** The number of Unicode code points of `text`: the length that Python gives for a string. */
  private def codePoints(text: String): Int = text.codePointCount(0, text.length)
