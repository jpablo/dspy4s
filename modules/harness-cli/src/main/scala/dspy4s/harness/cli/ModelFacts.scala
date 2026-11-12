package dspy4s.harness.cli

import zio.blocks.schema.json.Json

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.Try

/** What the endpoint of the model tells about it (CLI_SCREEN.md, 14.4): the size of its context window in tokens, and
  * its levels of the reasoning effort. A fact that the endpoint does not give is empty.
  */
final case class ModelFacts(window: Option[Long], levels: Vector[String]) derives CanEqual:

  /** The levels that `/effort` takes: `none`, then the levels of the endpoint, or `Effort.Common` with no list. */
  def choices: Vector[String] = (Effort.Off +: (if levels.isEmpty then Effort.Common else levels)).distinct

object ModelFacts:

  /** No facts: the endpoint did not answer, or it does not know the model. */
  val unknown: ModelFacts = ModelFacts(None, Vector.empty)

  /** The variable of the window of the model, which wins over the endpoint. */
  val WindowVariable: String = "DSPY_CONTEXT_WINDOW"

  /** The facts of the model `model` in `text`, the reply of `GET /models`: the entry of `data` whose id is `model`, or
    * ends with `/` and `model`, as `owner/model`. The window is `context_window`, which the private endpoint gives,
    * else `max_model_len`, which vLLM gives. The levels are `reasoning_effort_levels`. A text that is not such a reply,
    * or a reply with no entry of the model, gives `unknown`.
    */
  def parse(text: String, model: String): ModelFacts =
    val entries = Json.parse(text).toOption.flatMap(field(_, "data")).toVector.flatMap(items)
    entries.find(entry => field(entry, "id").exists(named(_, model))).fold(unknown) { entry =>
      val window = count(entry, "context_window").orElse(count(entry, "max_model_len"))
      val levels = field(entry, "reasoning_effort_levels").toVector.flatMap(items).collect {
        case Json.String(level) => level
      }
      ModelFacts(window, levels)
    }

  /** `fetched` with the window of `DSPY_CONTEXT_WINDOW` of `env`, when it is a whole number above 0. */
  def withEnv(fetched: ModelFacts, env: Map[String, String]): ModelFacts =
    env.get(WindowVariable).flatMap(_.trim.toLongOption).filter(_ > 0).fold(fetched)(w =>
      fetched.copy(window = Some(w))
    )

  /** The facts of `model` from `GET <base>/models` with the key `key`, or `unknown` after a failure, a status that is
    * not 200, or `timeout`. The key goes to the same base URL as the calls to the model.
    */
  def fetch(base: String, key: Option[String], model: String, timeout: Duration): ModelFacts =
    Try {
      val client = HttpClient.newBuilder().connectTimeout(timeout).build()
      try
        val request = HttpRequest.newBuilder(URI.create(base.stripSuffix("/") + "/models")).timeout(timeout).GET()
        key.filter(_.nonEmpty).foreach(k => request.header("Authorization", s"Bearer $k"))
        val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        if response.statusCode == 200 then parse(response.body, model) else unknown
      finally client.close()
    }.getOrElse(unknown)

  private def field(json: Json, name: String): Option[Json] =
    json match
      case Json.Object(fields) => fields.collectFirst { case (`name`, value) => value }
      case _                   => None

  private def items(json: Json): Vector[Json] =
    json match
      case Json.Array(values) => values.toVector
      case _                  => Vector.empty

  private def named(id: Json, model: String): Boolean =
    id match
      case Json.String(text) => text == model || text.endsWith(s"/$model")
      case _                 => false

  /** The whole number above 0 of the field `name` of `entry`, or none. */
  private def count(entry: Json, name: String): Option[Long] =
    field(entry, name).collect { case Json.Number(n) if n > 0 && n.isValidLong => n.toLongExact }
