package dspy4s.lm.contracts

import dspy4s.core.contracts.{DspyError, DynamicValues, LmUsage, ParseError, ValidationError}
import dspy4s.lm.providers.DynamicJson
import zio.blocks.schema.DynamicValue

/** Experimental. The request and the reply of a System One model ([[LanguageModel.supportsDecisionRequests]]), as an
  * [[LmRequest]] and an [[LmResponse]], so that the cache, the retries, the history, and the usage of
  * `ManagedLanguageModel` apply with no new code (decision 4 of D6; upstream `TypeSafe.__call__(state, questions)` in
  * `dspy/clients/typesafe.py`).
  */
object DecisionRequests:

  /** The option that holds the state. */
  val StateKey: String = "state"

  /** The option that holds the questions. */
  val QuestionsKey: String = "questions"

  /** The request of `state` and `questions` for `model`: an [[LmRequest]] with no messages, and the options `state` and
    * `questions`, in this order. The cache key and the history entry cover both.
    *
    * Each option holds the JSON form of its value: the value that the JSON codec of the wire reads back from the JSON
    * text of the value. Thus the request holds the values that the wire sends, and two values with one JSON text (for
    * example the numbers `0.0` and `0`) give one cache key. A value that has no JSON text (for example a number that is
    * not finite) stays as it is, and the model rejects it.
    */
  def request(model: String, state: DynamicValue, questions: DynamicValue.Record): LmRequest =
    val jsonQuestions = jsonForm(questions) match
      case record: DynamicValue.Record => record
      case _                           => questions
    LmRequest(model = model, options = DynamicValues.record(StateKey -> jsonForm(state), QuestionsKey -> jsonQuestions))

  /** The `state` and the `questions` of a decision request, or a `Left` when `request` has messages or lacks either
    * option.
    */
  def parts(request: LmRequest): Either[DspyError, (DynamicValue, DynamicValue.Record)] =
    if request.messages.nonEmpty then
      Left(ValidationError("A decision request has no messages: a System One model generates no text"))
    else
      val state     = DynamicValues.recordGet(request.options, StateKey)
      val questions = DynamicValues.recordGet(request.options, QuestionsKey)
      (state, questions) match
        case (Some(value), Some(record: DynamicValue.Record)) => Right((value, record))
        case (Some(_), Some(_))                               => Left(QuestionsError)
        case _                                                => Left(PartsError)

  /** The reply of a decision request: one output whose text is the JSON object of the answers, by question name. */
  def response(answers: DynamicValue.Record, usage: Option[LmUsage], modelName: Option[String]): LmResponse =
    LmResponse(
      outputs = Vector(LmOutput(text = DynamicValues.encodeJson(answers))),
      usage = usage,
      modelName = modelName
    )

  /** The answers of a reply: the JSON object in the text of its first output. A reply with no output, or with a text
    * that is not a JSON object, is a `Left`.
    */
  def answers(response: LmResponse): Either[DspyError, DynamicValue.Record] =
    response.outputs.headOption match
      case None         => Left(ParseError(Component, "The reply of the System One model has no output"))
      case Some(output) => DynamicJson.decode(output.text) match
          case Right(record: DynamicValue.Record) => Right(record)
          case _                                  => Left(ParseError(
              Component,
              "The reply of the System One model is not a JSON object of answers",
              raw = Some(output.text)
            ))

  /** The message for the request options `keys` that a System One model does not take (upstream
    * `DecisionAdapter._prepare`: "Unsupported TypeSafe generation settings: " and the Python text of the sorted keys).
    */
  def unsupportedSettings(keys: Vector[String]): String =
    s"Unsupported TypeSafe generation settings: ${keys.sorted.map(key => s"'$key'").mkString("[", ", ", "]")}."

  private val QuestionsError: DspyError = ValidationError("The questions of a decision request must be a JSON object")

  private val PartsError: DspyError = ValidationError("A decision request needs the options 'state' and 'questions'")

  /** The component of a parse error. */
  private val Component: String = "decision_reply"

  /** The JSON form of `value`, or `value` itself when it has no JSON text. */
  private def jsonForm(value: DynamicValue): DynamicValue =
    DynamicValues.tryEncodeJson(value).toOption.flatMap(DynamicJson.decode(_).toOption).getOrElse(value)
