package dspy4s.lm.contracts

import dspy4s.core.contracts.{DspyError, DynamicValues, LmUsage, ParseError, recordSchema}
import zio.blocks.schema.{DynamicValue, Schema}

/** Experimental. The payload of a decision request ([[LmRequest.decision]]; upstream `TypeSafe.__call__(state,
  * questions)` in `dspy/clients/typesafe.py`). The cache key and the history entry have both values.
  *
  * @param state
  *   the state of the decision: the instructions, the inputs, and the demos of the predictor
  * @param questions
  *   the questions by name
  */
final case class DecisionPayload(state: DynamicValue, questions: DynamicValue.Record) derives Schema

/** Experimental. The request and the reply of a System One model ([[LanguageModel.supportsDecisionRequests]]), as an
  * [[LmRequest]] and an [[LmResponse]], so that the cache, the retries, the history, and the usage of
  * `ManagedLanguageModel` apply with no new code (decision 4 of D6; upstream `TypeSafe.__call__(state, questions)` in
  * `dspy/clients/typesafe.py`).
  */
object DecisionRequests:

  /** The request of `state` and `questions` for `model`: an [[LmRequest]] with no messages, no options, and the
    * [[DecisionPayload]] in [[LmRequest.decision]]. The cache key and the history entry cover the payload.
    *
    * The payload holds the JSON form of each value: the value that the JSON codec of the wire reads back from the JSON
    * text of the value. Thus the request holds the values that the wire sends, and two values with one JSON text (for
    * example the numbers `0.0` and `0`) give one cache key. A value that has no JSON text (for example a number that is
    * not finite) stays as it is, and the model rejects it.
    */
  def request(model: String, state: DynamicValue, questions: DynamicValue.Record): LmRequest =
    val jsonQuestions = jsonForm(questions) match
      case record: DynamicValue.Record => record
      case _                           => questions
    LmRequest(model = model, decision = Some(DecisionPayload(jsonForm(state), jsonQuestions)))

  /** The reply of a decision request: one output with the typed answers by question name ([[LmOutput.answers]]), and
    * the JSON text of the answers as its text (upstream `outputs` of the history entry). The history and the stream
    * read the text.
    */
  def response(answers: DynamicValue.Record, usage: Option[LmUsage], modelName: Option[String]): LmResponse =
    LmResponse(
      outputs = Vector(LmOutput(text = DynamicValues.encodeJson(answers), answers = Some(answers))),
      usage = usage,
      modelName = modelName
    )

  /** The answers of a reply: the typed answers of its first output ([[LmOutput.answers]]). The function does not parse
    * the text. A reply with no output, or with no typed answers in its first output, is a `Left`.
    */
  def answers(response: LmResponse): Either[DspyError, DynamicValue.Record] =
    response.outputs.headOption match
      case None         => Left(ParseError(Component, "The reply of the System One model has no output"))
      case Some(output) => output.answers.toRight(
          ParseError(Component, "The reply of the System One model has no typed answers", raw = Some(output.text))
        )

  /** The message for the request options `keys` that a System One model does not take (upstream
    * `DecisionAdapter._prepare`: "Unsupported TypeSafe generation settings: " and the Python text of the sorted keys).
    */
  def unsupportedSettings(keys: Vector[String]): String =
    s"Unsupported TypeSafe generation settings: ${keys.sorted.map(key => s"'$key'").mkString("[", ", ", "]")}."

  /** The component of a parse error. */
  private val Component: String = "decision_reply"

  /** The JSON form of `value`, or `value` itself when it has no JSON text. */
  private def jsonForm(value: DynamicValue): DynamicValue =
    DynamicValues.tryEncodeJson(value).toOption.flatMap(DynamicValues.decodeJson).getOrElse(value)
