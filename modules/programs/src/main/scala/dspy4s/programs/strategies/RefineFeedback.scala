package dspy4s.programs.strategies

import dspy4s.adapters.internal.JsonTextScan
import dspy4s.core.contracts.{
  DspyError,
  DynamicValues,
  FieldSpec,
  PythonText,
  RuntimeContext,
  SignatureLayout,
  TraceEntry,
  :=
}
import dspy4s.core.data.RawPrediction
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.Prediction
import dspy4s.programs.optimization.OptimizableView
import dspy4s.signatures.{Shape, Signature}
import zio.blocks.schema.{DynamicValue, Schema}

/** OfferFeedback critic and its translation between runtime traces and per-module advice. */
private[programs] object RefineFeedback:

  /** The critic's input: the six input fields of [[layout]], with the same names. */
  final case class OfferFeedbackInputs(
      program_inputs    : String,
      program_trajectory: String,
      program_outputs   : String,
      reward_value      : Double,
      target_threshold  : Double,
      module_names      : String
  ) derives Schema

  /** The critic's output. `discussion` is prompt-guidance only ([[generateAdvice]] never reads it), which the lenient
    * shape below reflects.
    */
  final case class OfferFeedbackAdvice(discussion: String, advice: String)

  /** The type of the critic predict. */
  type Critic = Predict[OfferFeedbackInputs, OfferFeedbackAdvice]

  /** The OfferFeedback signature layout: a paraphrase of upstream's `OfferFeedback` docstring, with the input fields
    * dspy4s can ground from the runtime (program I/O, the runtime trajectory, reward + threshold, and the
    * `module_names` for which advice is sought) and the `discussion` / `advice` outputs. Per parity, `advice` is a JSON
    * object keyed by module name (`{module_name: advice}`).
    */
  val layout: SignatureLayout = SignatureLayout.create(
    name = "OfferFeedback",
    inputFields = Vector(
      FieldSpec(
        "program_inputs",
        description = Some("The inputs to the program that we are analyzing")
      ),
      FieldSpec(
        "program_trajectory",
        description = Some("The trajectory of the program's execution, showing each module's I/O")
      ),
      FieldSpec(
        "program_outputs",
        description = Some("The outputs of the program that we are analyzing")
      ),
      FieldSpec(
        "reward_value",
        description = Some("The reward value assigned to the program's outputs")
      ),
      FieldSpec(
        "target_threshold",
        description = Some("The target threshold for the reward function")
      ),
      FieldSpec(
        "module_names",
        description = Some("The names of the modules in the program, for which we seek advice")
      )
    ),
    outputFields = Vector(
      FieldSpec(
        "discussion",
        description = Some("Discussing blame of where each module went wrong, if it did")
      ),
      FieldSpec(
        "advice",
        description = Some(
          "A JSON object mapping each module name (from module_names) to concrete, actionable advice for that " +
            "module: the specific scenarios in which it made mistakes and what it should do differently on the " +
            "same or similar inputs in the future. Each module will NOT see its own history, so its advice must be " +
            "entirely self-contained. Use \"N/A\" for a module that is not to blame. Example: " +
            "{\"module_a\": \"...\", \"module_b\": \"N/A\"}."
        )
      )
    ),
    instructions = Some(
      "Assign blame for the final reward being below the threshold to each named module. Then prescribe " +
        "concrete, actionable advice for how each module should act on its future input if it were to receive " +
        "the same or similar inputs on a retry. A module will not see its own history, so it must rely entirely " +
        "on concrete and actionable advice from you to avoid the same mistake. Return the advice as a JSON " +
        "object keyed by module name; if a module is not to blame, its advice should be \"N/A\"."
    )
  ).getOrElse(throw new IllegalStateException("OfferFeedback layout failed to construct"))

  /** Hand-written lenient output shape: advice is required, while discussion defaults to an empty string. */
  private val outputShape: Shape[OfferFeedbackAdvice] = new Shape[OfferFeedbackAdvice]:
    val fieldSpecs: Vector[FieldSpec] = layout.outputFields

    def encode(value: OfferFeedbackAdvice): DynamicValue.Record =
      DynamicValues.record("discussion" := value.discussion, "advice" := value.advice)

    def decode(raw: DynamicValue.Record): Either[DspyError, OfferFeedbackAdvice] =
      RawPrediction(values = raw).asString("advice").map { advice =>
        OfferFeedbackAdvice(
          discussion = DynamicValues.recordGet(raw, "discussion").map(DynamicValues.renderText).getOrElse(""),
          advice = advice
        )
      }

  /** The critic signature, preserving the hand-built descriptions and lenient output decoding. */
  val signature: Signature[OfferFeedbackInputs, OfferFeedbackAdvice] = Signature(
    layout = layout,
    inputShape = Shape.derived[OfferFeedbackInputs],
    outputShape = outputShape
  )

  /** `value` as upstream `Refine.forward` gives it to the critic: `orjson.dumps` with an indent of 2. Non-ASCII stays
    * as it is.
    */
  private def jsonText(value: DynamicValue): String = PythonText.json(value, indent = Some(2))

  /** The trajectory of `trace` as JSON text (upstream `Refine.forward`): a list of objects with the keys `module_name`,
    * `inputs`, and `outputs`, one for each predictor call. An entry with no [[TraceEntry.predictor]] key (a wrapper
    * module, or a module inside a predictor) is not in the list. The name is the name in `named` of the view with the
    * structure of the key, else the component of the entry.
    */
  def renderTrajectory(trace: Vector[TraceEntry], named: Vector[(String, OptimizableView)]): String =
    val entries = trace.flatMap { entry =>
      entry.predictor.map { key =>
        val found = named.find((_, view) => view.layout.withInstructions(None).equals(key.structure))
        val name  = found.fold(entry.component)(_._1)
        DynamicValues.record("module_name" := name, "inputs" -> entry.inputs, "outputs" -> entry.outputs)
      }
    }
    jsonText(DynamicValues.sequence(entries))

  /** Run the critic under the ambient runtime context (NOT under the hint adapter) and decode its per-module advice.
    * The critic reads the inputs and the outputs of the program, the trajectory of the attempt, the reward value, the
    * threshold, and the names of `named`. Each of them that is not a number is JSON text ([[jsonText]]).
    *
    * `programInputs` are the observed inputs of the program ([[dspy4s.programs.contracts.Module.observedInputs]]). A
    * program that observes no inputs, such as `BestOfN`, gives `{}`. The trajectory still shows the inputs of each
    * predictor.
    */
  def generateAdvice[O](
      critic       : Critic,
      programInputs: DynamicValue.Record,
      prediction   : Prediction[O],
      trace        : Vector[TraceEntry],
      reward       : Double,
      threshold    : Double,
      named        : Vector[(String, OptimizableView)]
  )(using RuntimeContext): Either[DspyError, Map[String, String]] =
    val moduleNames = named.map(_._1)
    critic(ProgramCall(input =
      OfferFeedbackInputs(
        program_inputs = jsonText(programInputs),
        program_trajectory = renderTrajectory(trace, named),
        program_outputs = jsonText(prediction.raw.values),
        reward_value = reward,
        target_threshold = threshold,
        module_names = jsonText(DynamicValues.sequence(moduleNames.map(DynamicValues.string)))
      )
    )).map(result => parseAdvice(result.output.advice, moduleNames))

  /** Parse the OfferFeedback `advice` output into a per-module advice map. The usual output is a JSON object
    * `{module_name: advice}`. The parse finds the first object ([[JsonTextScan.firstObject]]), so prose or code fences
    * can be around it. An output with no JSON object is uniform advice: each module gets the same text.
    */
  def parseAdvice(raw: String, moduleNames: Vector[String]): Map[String, String] =
    JsonTextScan.firstObject(raw).flatMap(decodeStringMap).filter(_.nonEmpty)
      .getOrElse(moduleNames.iterator.map(_ -> raw.trim).toMap)

  private def decodeStringMap(json: String): Option[Map[String, String]] =
    DynamicValues.parseJsonRecord(json).map { record =>
      record.fields.iterator.map((name, value) => name -> DynamicValues.renderText(value)).toMap
    }
