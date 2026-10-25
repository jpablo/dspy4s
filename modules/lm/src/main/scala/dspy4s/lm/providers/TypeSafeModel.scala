package dspy4s.lm.providers

import dspy4s.core.contracts.{
  :=,
  DspyError,
  DynamicValues,
  LmAuthError,
  LmErrorDetails,
  LmInvalidRequestError,
  LmProviderError,
  LmProviderResponseError,
  LmServerError,
  LmUnsupportedModelError,
  LmUsage,
  RuntimeContext
}
import dspy4s.lm.contracts.{DecisionRequests, LanguageModel, LmMode, LmRequest, LmResponse}
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

import java.time.Instant
import java.util.Locale

/** Experimental. The client of the TypeSafe System One API, for the Jev models (decision 4 of D6; upstream
  * `dspy.experimental.TypeSafe` in `dspy/clients/typesafe.py`, and the wire rules of the vendored lm15 provider in
  * `dspy/_vendor/lm15/providers/typesafe.py`). Jev answers decision questions with probabilities and generates no text,
  * so this model accepts only decision requests ([[dspy4s.lm.contracts.DecisionRequests]]). Wrap it in a
  * `ManagedLanguageModel` for the cache, the retries, the history, and the usage.
  *
  * The wire:
  *   - `POST <baseUrl>/v1/systemone` with the headers `Authorization: Bearer <apiKey>` and `Content-Type:
  *     application/json`, and the body `{"model", "state", "questions"}`.
  *   - The reply `{"model", "answers", "usage": {"input_tokens", "output_tokens"}}`. The answers must have exactly the
  *     question names. Each answer has the `type` of its question (`noul`, `score`, or `choice`); a `noul` answer has a
  *     finite `noul` in [0, 1]; a `score` or `choice` answer has `probabilities` with one finite number in [0, 1] for
  *     each declared key and no other key; a `choice` answer has a `choice` that is a declared key. A reply that breaks
  *     a rule is an [[dspy4s.core.contracts.LmProviderResponseError]] with the path of the fault.
  *   - The response has one output with the JSON text of the answers as the reply gave them, the usage (the input
  *     tokens as prompt tokens, the output tokens as completion tokens), and the model of the reply.
  *   - The header `x-typesafe-request-id` gives the request id of an error.
  *   - An error status maps as upstream `normalize_error`: 401 (or the code `authentication_error`) is an
  *     [[dspy4s.core.contracts.LmAuthError]]; 429 is an [[dspy4s.core.contracts.LmRateLimitError]]; 400 with "unknown
  *     model" in the message is an [[dspy4s.core.contracts.LmUnsupportedModelError]]; 400 or 422 is an
  *     [[dspy4s.core.contracts.LmInvalidRequestError]]; 500 or more is an [[dspy4s.core.contracts.LmServerError]]. The
  *     message is `detail.message` of the body, or `<loc>: <msg>` of the first entry of a `detail` list (with the
  *     `body` part of `loc` left out), else the body text.
  *   - A request that is not a decision request (it has messages, or it lacks the state or the questions) is an
  *     [[dspy4s.core.contracts.LmInvalidRequestError]] before the wire: Jev generates no text. A request with other
  *     options (for example `temperature`) is an [[dspy4s.core.contracts.LmInvalidRequestError]] too (upstream
  *     "Unsupported TypeSafe generation settings").
  *
  * The other rules of the vendored provider:
  *   - The keys of a `score` question are the level indexes `"0"` to `"N-1"` of its `criteria` array; the keys of a
  *     `choice` question are the keys of its `criteria` object. A request with no question, or with a question that
  *     does not have one of these forms, is an [[dspy4s.core.contracts.LmInvalidRequestError]] before the wire
  *     (upstream `_questions`).
  *   - A reply with no `usage` (or a null `usage`) gives no usage. A token count that the reply does not give is zero.
  *     A reply with no `model` (or a null `model`) has the model of the request (upstream `parse_response`).
  *   - Each other error status maps as upstream `map_http_error` (`OpenAiErrors.byStatus`): 403 is an
  *     [[dspy4s.core.contracts.LmAuthError]], 402 an [[dspy4s.core.contracts.LmBillingError]], 408 an
  *     [[dspy4s.core.contracts.LmTimeoutError]], 404, 409, or 413 an [[dspy4s.core.contracts.LmInvalidRequestError]],
  *     and each other status an [[dspy4s.core.contracts.LmProviderResponseError]]. The rule for 500 or more comes
  *     first, so 504 and each status of 600 or more give an [[dspy4s.core.contracts.LmServerError]] here, not the class
  *     of the shared table.
  *   - An error gets the status, the provider `typesafe`, the code `detail.error_type`, the request id (the header
  *     `x-typesafe-request-id`, else a request id header of `_attach_error_metadata`), and the retry hint of the
  *     `Retry-After` header (upstream `_attach_retry_after`).
  *
  * @param model
  *   the Jev model, for example `jev-latest`
  * @param apiKey
  *   the API key. It goes only to the `Authorization` header: never to the history, the cache key, or an error.
  */
final case class TypeSafeModel(
    model    : String        = TypeSafeModel.DefaultModel,
    apiKey   : String,
    baseUrl  : String        = TypeSafeModel.DefaultBaseUrl,
    transport: HttpTransport = HttpTransport.jdk(HttpTimeouts(read = TypeSafeModel.DefaultTimeout))
) extends LanguageModel:

  override val id: String                        = model
  override val mode: LmMode                      = LmMode.Chat
  override def supportsDecisionRequests: Boolean = true

  /** The provider and the base URL with no slash at its end. Upstream puts both in the cache key (`_request` in
    * `dspy/clients/typesafe.py`), so two models with one Jev model and different endpoints do not share an entry.
    */
  override def cacheIdentity: DynamicValue.Record =
    DynamicValues.record("provider" := TypeSafeModel.Provider, "base_url" := root)

  /** Check the decision request, send it, and read the reply with the rules of the wire above. Each failure is a
    * `Left`, never an exception.
    */
  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    for
      wire     <- TypeSafeModel.wireRequest(request)
      reply    <- send(wire.body, request)
      response <-
        if reply.isSuccess then TypeSafeModel.parseReply(reply, wire.questions, request)
        else Left(TypeSafeModel.statusError(reply, request))
    yield response

  /** Send `body`. A failure of the transport gets the model and the provider. */
  private def send(body: String, request: LmRequest): Either[DspyError, HttpResponse] =
    transport.sendJson(endpoint, headers, body)
      .left.map(OpenAiErrors.withRequestDetails(_, Some(request.model), TypeSafeModel.Provider))

  /** The base URL with no slash at its end (upstream `base_url.rstrip("/")`). */
  private def root: String = baseUrl.reverse.dropWhile(_ == '/').reverse

  /** The URL of the System One API. */
  private def endpoint: String = s"$root/v1/systemone"

  private def headers: Map[String, String] =
    Map("Authorization" -> s"Bearer $apiKey", "Content-Type" -> "application/json")

  /** Close the transport. */
  override def close(): Unit = transport.close()

  /** The API key is not part of the text form. */
  override def toString: String = s"TypeSafeModel($model, $baseUrl)"

object TypeSafeModel:

  /** The default model (upstream `jev-latest`). */
  val DefaultModel: String = "jev-latest"

  /** The default endpoint (upstream `https://api.typesafe.ai`). */
  val DefaultBaseUrl: String = "https://api.typesafe.ai"

  /** The default time limit for each operation (upstream 10 seconds). */
  val DefaultTimeout: scala.concurrent.duration.FiniteDuration = scala.concurrent.duration.FiniteDuration(10, "seconds")

  /** A model from the environment, as upstream: the key from `TYPESAFE_API_KEY`, the model from
    * `TYPESAFE_DEFAULT_MODEL` (else [[DefaultModel]]), and the endpoint from `TYPESAFE_BASE_URL` (else
    * [[DefaultBaseUrl]]). A key that is not set, or empty, is an [[dspy4s.core.contracts.LmNotConfiguredError]].
    *
    * @param env
    *   the environment to read, by default `sys.env`
    */
  def fromEnv(env: Map[String, String] = sys.env): Either[DspyError, TypeSafeModel] =
    def setting(name: String): Option[String] = env.get(name).filter(_.nonEmpty)
    setting(ApiKeyVariable) match
      case Some(key) => Right(TypeSafeModel(
          model = setting("TYPESAFE_DEFAULT_MODEL").getOrElse(DefaultModel),
          apiKey = key,
          baseUrl = setting("TYPESAFE_BASE_URL").getOrElse(DefaultBaseUrl)
        ))
      case None => Left(OpenAiErrors.notConfigured(ApiKeyVariable, Provider))

  private val ApiKeyVariable: String = "TYPESAFE_API_KEY"

  /** The provider name of the errors and of the cache identity (upstream `provider = "typesafe"`). */
  private val Provider: String = "typesafe"

  /** The longest body excerpt in an error message (upstream `body.strip()[:500]`). */
  private val ExcerptLength: Int = 500

  /** The question of one decision output, and the keys that its answer must have. */
  private enum Question derives CanEqual:
    case Noul
    case Score(keys: Vector[String])
    case Choice(keys: Vector[String])

    /** The `type` of the question and of its answer. */
    def typeName: String =
      this match
        case Noul      => "noul"
        case Score(_)  => "score"
        case Choice(_) => "choice"

  /** The body of a request, and its questions by name, in the order of the request. */
  private final case class Wire(body: String, questions: Vector[(String, Question)])

  /** The body of `request`, or an [[LmInvalidRequestError]] when `request` is not a decision request that the wire can
    * send.
    */
  private def wireRequest(request: LmRequest): Either[DspyError, Wire] =
    def invalid(message: String): DspyError = LmInvalidRequestError(message, details(request))
    for
      parts <- DecisionRequests.parts(request).left.map(error =>
                 invalid(s"TypeSafe Jev answers decision requests only, and generates no text: ${error.message}")
               )
      extra = DynamicValues.recordKeys(request.options)
                .filterNot(key => key == DecisionRequests.StateKey || key == DecisionRequests.QuestionsKey)
      _         <- Either.cond(extra.isEmpty, (), invalid(DecisionRequests.unsupportedSettings(extra)))
      questions <- questionsOf(parts._2).left.map(invalid)
      body      <- encode(request.model, parts._1, parts._2).left.map(invalid)
    yield Wire(body, questions)

  /** The questions of a request, or the text of the fault. */
  private def questionsOf(questions: DynamicValue.Record): Either[String, Vector[(String, Question)]] =
    if questions.fields.isEmpty then Left("A decision request needs at least one question: Jev cannot generate values")
    else
      DynamicValues.recordEntries(questions).foldLeft[Either[String, Vector[(String, Question)]]](Right(Vector.empty)) {
        case (acc, (name, value)) =>
          acc.flatMap(read => questionOf(name, value).map(question => read :+ (name -> question)))
      }

  private def questionOf(name: String, value: DynamicValue): Either[String, Question] =
    val record   = DynamicJson.asRecord(value)
    val kind     = record.flatMap(DynamicJson.field(_, "type")).flatMap(DynamicJson.asString)
    val criteria = record.flatMap(DynamicJson.field(_, "criteria"))
    (kind, criteria) match
      case (Some("noul"), _)                                    => Right(Question.Noul)
      case (Some("score"), Some(levels: DynamicValue.Sequence)) =>
        Right(Question.Score(levels.elements.indices.map(_.toString).toVector))
      case (Some("choice"), Some(labels: DynamicValue.Record)) =>
        Right(Question.Choice(DynamicValues.recordKeys(labels)))
      case _ => Left(
          s"The question '$name' must be a noul question, a score question with a criteria array, or a choice " +
            "question with a criteria object"
        )

  /** The JSON text of the body. Each null stays: a choice criterion with no description is a JSON null. A body with no
    * JSON text (for example one with a NaN, or one nested too deep) gives the text of the error of
    * `DynamicJson.encodeExactChecked`.
    */
  private def encode(model: String, state: DynamicValue, questions: DynamicValue.Record): Either[String, String] =
    val body = DynamicValues.record(
      "model"                       := model,
      DecisionRequests.StateKey     -> state,
      DecisionRequests.QuestionsKey -> questions
    )
    DynamicJson.encodeExactChecked(body).left.map(_.message)

  /** The response of a reply with a status from 200 to 299, with the reply rules of upstream `parse_response`. */
  private def parseReply(
      reply    : HttpResponse,
      questions: Vector[(String, Question)],
      request  : LmRequest
  ): Either[DspyError, LmResponse] =
    def invalid(path: String, detail: String): DspyError =
      LmProviderResponseError(s"malformed systemone reply at $path: $detail", replyDetails(reply, request))

    def probability(value: Option[DynamicValue], path: String): Either[DspyError, Unit] =
      Either.cond(
        value.flatMap(DynamicValues.asDouble).exists(p => p >= 0 && p <= 1),
        (),
        invalid(path, "expected a finite number in [0, 1]")
      )

    def distribution(answer: DynamicValue.Record, keys: Vector[String], path: String): Either[DspyError, Unit] =
      DynamicJson.field(answer, "probabilities") match
        case Some(probabilities: DynamicValue.Record) if DynamicValues.recordKeys(probabilities).toSet == keys.toSet =>
          keys.foldLeft[Either[DspyError, Unit]](Right(())) { (acc, key) =>
            acc.flatMap(_ =>
              probability(DynamicJson.field(probabilities, key), s"$path.probabilities.$key")
            )
          }
        case _ =>
          Left(invalid(s"$path.probabilities", "expected one probability for every declared key, and no other keys"))

    def checkAnswer(name: String, question: Question, answer: Option[DynamicValue]): Either[DspyError, Unit] =
      val path = s"answers.$name"
      answer.flatMap(DynamicJson.asRecord) match
        case None         => Left(invalid(path, "expected an answer object"))
        case Some(record) =>
          val kind = DynamicJson.field(record, "type").flatMap(DynamicJson.asString)
          if !kind.contains(question.typeName) then Left(invalid(s"$path.type", s"expected '${question.typeName}'"))
          else
            question match
              case Question.Noul         => probability(DynamicJson.field(record, "noul"), s"$path.noul")
              case Question.Score(keys)  => distribution(record, keys, path)
              case Question.Choice(keys) => distribution(record, keys, path).flatMap { _ =>
                  val pick = DynamicJson.field(record, "choice").flatMap(DynamicJson.asString)
                  Either.cond(
                    pick.exists(keys.contains),
                    (),
                    invalid(s"$path.choice", "expected a declared choice key")
                  )
                }

    def tokens(usage: DynamicValue.Record, key: String): Either[DspyError, Long] =
      val wrong = invalid("usage", s"expected '$key' to be an integer that is not negative")
      DynamicJson.field(usage, key) match
        case None | Some(_: DynamicValue.Null.type)                         => Right(0L)
        case Some(DynamicValue.Primitive(PrimitiveValue.Int(n))) if n >= 0  => Right(n.toLong)
        case Some(DynamicValue.Primitive(PrimitiveValue.Long(n))) if n >= 0 => Right(n)
        case Some(_)                                                        => Left(wrong)

    def usageOf(data: DynamicValue): Either[DspyError, Option[LmUsage]] =
      DynamicJson.field(data, "usage") match
        case None | Some(_: DynamicValue.Null.type) => Right(None)
        case Some(usage: DynamicValue.Record)       =>
          for
            input  <- tokens(usage, "input_tokens")
            output <- tokens(usage, "output_tokens")
          yield Some(LmUsage(totalTokens = input + output, promptTokens = input, completionTokens = output))
        case Some(_) => Left(invalid("usage", "expected an object or null"))

    def modelOf(data: DynamicValue): Either[DspyError, String] =
      DynamicJson.field(data, "model") match
        case None | Some(_: DynamicValue.Null.type) => Right(request.model)
        case Some(value)                            =>
          DynamicJson.asString(value).filter(_.nonEmpty).toRight(invalid("model", "expected a non-empty string"))

    for
      data    <- DynamicJson.decode(reply.body).left.map(_ => invalid("$", "the reply is not JSON"))
      _       <- DynamicJson.asRecord(data).toRight(invalid("$", "expected an object"))
      answers <- DynamicJson.field(data, "answers").flatMap(DynamicJson.asRecord)
                   .toRight(invalid("answers", "expected an object containing every declared judgment"))
      _ <- Either.cond(
             DynamicValues.recordKeys(answers).toSet == questions.map(_._1).toSet,
             (),
             invalid("answers", "keys must match the declared judgments exactly")
           )
      _ <- questions.foldLeft[Either[DspyError, Unit]](Right(())) { case (acc, (name, question)) =>
             acc.flatMap(_ => checkAnswer(name, question, DynamicJson.field(answers, name)))
           }
      usage     <- usageOf(data)
      modelName <- modelOf(data)
    yield DecisionRequests.response(answers, usage, Some(modelName))

  /** The error of a reply with a status outside 200 to 299 (upstream `normalize_error`, then `map_http_error`). The
    * checks of `normalize_error` come first: 401 or the code `authentication_error`, 400 with "unknown model" in the
    * message, and 500 or more. Each other status gets the class of the shared table (`OpenAiErrors.byStatus`).
    */
  private def statusError(reply: HttpResponse, request: LmRequest): LmProviderError =
    val (message, code) = errorText(reply.status, reply.body)
    val details         = replyDetails(reply, request).copy(providerCode = code)
    val status          = reply.status
    if status == 401 || code.contains("authentication_error") then LmAuthError(message, details)
    else if status == 400 && message.toLowerCase(Locale.ROOT).contains("unknown model") then
      LmUnsupportedModelError(message, details)
    else if status >= 500 then LmServerError(message, details)
    else OpenAiErrors.byStatus(status)(message, details)

  /** The message and the provider code of an error body (upstream `normalize_error`). */
  private def errorText(status: Int, body: String): (String, Option[String]) =
    val excerpt  = body.strip().take(ExcerptLength)
    val fallback = if excerpt.isEmpty then s"HTTP $status" else excerpt
    DynamicJson.decode(body).toOption.flatMap(DynamicJson.field(_, "detail")) match
      case Some(detail: DynamicValue.Record) =>
        val message = DynamicJson.field(detail, "message").flatMap(DynamicJson.asString).getOrElse(fallback)
        (message, DynamicJson.field(detail, "error_type").flatMap(DynamicJson.asString))
      case Some(detail: DynamicValue.Sequence) if detail.elements.nonEmpty =>
        val first = detail.elements.headOption.flatMap(DynamicJson.asRecord).getOrElse(DynamicValue.Record.empty)
        val loc   = DynamicJson.field(first, "loc").map(DynamicJson.asSequence).getOrElse(Vector.empty)
          .filterNot(part => DynamicJson.asString(part).contains("body"))
          .map(pythonText)
          .mkString(".")
        val text = DynamicJson.field(first, "msg").map(pythonText)
        (if loc.nonEmpty then s"$loc: ${text.getOrElse("validation error")}" else text.getOrElse(fallback), None)
      case _ => (fallback, None)

  /** The text of a JSON value, as Python `str` gives it for a string or a number. */
  private def pythonText(value: DynamicValue): String =
    DynamicJson.asString(value).getOrElse(DynamicValues.renderText(value))

  /** The details of an error before the wire. */
  private def details(request: LmRequest): LmErrorDetails =
    LmErrorDetails(model = Some(request.model), provider = Some(Provider))

  /** The details of an error of a reply: the status, the request id, and the retry hint of the headers. */
  private def replyDetails(reply: HttpResponse, request: LmRequest): LmErrorDetails =
    val lowered = OpenAiErrors.lowerCase(reply.headers)
    details(request).copy(
      status = Some(reply.status),
      requestId = OpenAiErrors.requestId(lowered, preferred = Seq("x-typesafe-request-id")),
      retryAfter = OpenAiErrors.retryAfter(lowered, Instant.now())
    )
