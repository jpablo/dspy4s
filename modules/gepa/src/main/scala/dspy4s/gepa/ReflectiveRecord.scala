package dspy4s.gepa

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.PythonText
import dspy4s.core.contracts.:=
import zio.blocks.schema.DynamicValue

/** One example that the reflection LM reads to improve the instruction of a component: gepa's
  * `{Inputs, Generated Outputs, Feedback}` record (dspy's `ReflectiveExample`). The reflective dataset of a component
  * is a sequence of these records. See PORT_GAPS G-12 and G-45.
  *
  * The record holds values, not text. The [[InstructionProposer]] selects the text form.
  *
  * @param inputs
  *   the input values of one predictor call of one trajectory. An input of the type
  *   [[dspy4s.core.contracts.TypeRef.history]] is under the key [[ReflectiveRecord.ContextKey]], and that key is first.
  * @param generatedOutputs
  *   the record of the output values of that call. For a call that failed, it is one string: the raw response of the
  *   model in a fence, or a note that the call gave no output.
  * @param feedback
  *   the feedback of the [[dspy4s.gepa.contracts.FeedbackMetric]] for that predictor. For a call that failed, it is the
  *   output structure to follow, or the error of the call.
  *
  * The record of a call that failed exists only with `addFormatFailureAsFeedback`.
  */
final case class ReflectiveRecord(
    inputs          : DynamicValue.Record,
    generatedOutputs: DynamicValue,
    feedback        : String
):

  /** The record with the gepa keys, in the gepa order: `Inputs`, `Generated Outputs`, `Feedback`. */
  private def toRecord: DynamicValue.Record =
    DynamicValues.record(
      ReflectiveRecord.InputsKey   -> inputs,
      ReflectiveRecord.OutputsKey  -> generatedOutputs,
      ReflectiveRecord.FeedbackKey := feedback
    )

object ReflectiveRecord:
  private val InputsKey   = "Inputs"
  private val OutputsKey  = "Generated Outputs"
  private val FeedbackKey = "Feedback"

  /** The key of [[ReflectiveRecord.inputs]] that holds the conversation history of the call (dspy's `"Context"`). */
  val ContextKey: String = "Context"

  /** The JSON text of `records`: one JSON array of objects, on one line. Each object has the keys `Inputs`,
    * `Generated Outputs`, and `Feedback`, in that order. The form is that of Python's
    * `json.dumps(records, ensure_ascii=False)` ([[dspy4s.core.contracts.PythonText.json]]), which is the text that the
    * upstream JSON adapter gives to the reflection LM.
    */
  def toJson(records: Vector[ReflectiveRecord]): String =
    PythonText.json(DynamicValues.sequence(records.map(_.toRecord)))
