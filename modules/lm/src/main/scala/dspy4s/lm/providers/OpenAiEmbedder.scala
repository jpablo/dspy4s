package dspy4s.lm.providers

import dspy4s.core.contracts.ContextWindowExceededError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.updated
import dspy4s.lm.contracts.Embedder
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue

/** OpenAI embeddings provider (`POST /embeddings`) — the hosted-model half of `dspy.Embedder` (G-10). Splits the
  * input into `batchSize`-sized requests (upstream's `batch_size`, default 200) and concatenates the rows, each
  * row ordered by the response's `index` field. `options` carries extra request fields (e.g. `dimensions`),
  * merged into every payload. */
final case class OpenAiEmbedder(
    model: String,
    apiKey: String,
    baseUrl: String = OpenAiClient.defaultBaseUrl,
    transport: HttpTransport = HttpTransport.jdk(),
    batchSize: Int = 200,
    options: DynamicValue.Record = DynamicValue.Record.empty,
    embeddingsEndpoint: String = "/embeddings"
) extends Embedder:
  require(batchSize > 0, "batchSize must be > 0")

  override def id: String = model

  override def embed(texts: Vector[String])(using RuntimeContext): Either[DspyError, Vector[Vector[Float]]] =
    if texts.isEmpty then Right(Vector.empty)
    else
      texts.grouped(batchSize).foldLeft[Either[DspyError, Vector[Vector[Float]]]](Right(Vector.empty)) {
        (acc, batch) => acc.flatMap(rows => embedBatch(batch).map(rows ++ _))
      }

  private def embedBatch(batch: Seq[String]): Either[DspyError, Vector[Vector[Float]]] =
    val payload = options
      .updated("model", DynamicValues.fromAny(model))
      .updated("input", DynamicValues.fromAny(batch.toList))
    val url     = s"${baseUrl.stripSuffix("/")}$embeddingsEndpoint"
    val headers = Map("Authorization" -> s"Bearer $apiKey")
    transport.sendJson(url, headers, DynamicJson.encode(payload)).flatMap { response =>
      if response.status < 200 || response.status >= 300 then Left(statusError(response.status, response.body))
      else DynamicJson.decode(response.body).flatMap(parseRows(_, expected = batch.size))
    }

  /** Map a non-2xx like the chat client: HTTP 400 with a context-window marker becomes the typed error. */
  private def statusError(status: Int, body: String): DspyError =
    if status == 400 && OpenAiClient.isContextWindowError(body) then
      ContextWindowExceededError(model = Some(model))
    else RuntimeError("openai_embeddings", s"OpenAI embeddings request failed with status $status: $body")

  /** Extract `data[*].embedding` ordered by `data[*].index` (the authoritative row order). */
  private def parseRows(raw: DynamicValue, expected: Int): Either[DspyError, Vector[Vector[Float]]] =
    val rows = raw match
      case rec: DynamicValue.Record =>
        DynamicValues.recordGet(rec, "data") match
          case Some(seq: DynamicValue.Sequence) =>
            val parsed = seq.elements.iterator.flatMap {
              case item: DynamicValue.Record =>
                for
                  row   <- DynamicValues.recordGet(item, "embedding").flatMap(floatRow)
                  index <- DynamicValues.recordGet(item, "index").flatMap(asInt)
                yield index -> row
              case _ => None
            }.toVector
            parsed.sortBy(_._1).map(_._2)
          case _ => Vector.empty
      case _ => Vector.empty
    if rows.size == expected then Right(rows)
    else Left(ParseError("openai_embeddings", s"Expected $expected embedding rows, got ${rows.size}"))

  private def floatRow(dv: DynamicValue): Option[Vector[Float]] = dv match
    case seq: DynamicValue.Sequence =>
      val floats = seq.elements.iterator.flatMap(asFloat).toVector
      Option.when(floats.size == seq.elements.size)(floats)
    case _ => None

  // Direct PrimitiveValue matching: the dynamic JSON codec may decode numbers as BigDecimal/BigInt, which
  // `DynamicValues.toAny` does not surface as numbers.
  private def asFloat(dv: DynamicValue): Option[Float] = dv match
    case DynamicValue.Primitive(p) =>
      p match
        case PrimitiveValue.Double(n)     => Some(n.toFloat)
        case PrimitiveValue.Float(n)      => Some(n)
        case PrimitiveValue.Int(n)        => Some(n.toFloat)
        case PrimitiveValue.Long(n)       => Some(n.toFloat)
        case PrimitiveValue.BigDecimal(n) => Some(n.toFloat)
        case PrimitiveValue.BigInt(n)     => Some(n.toFloat)
        case PrimitiveValue.Short(n)      => Some(n.toFloat)
        case PrimitiveValue.Byte(n)       => Some(n.toFloat)
        case _                            => None
    case _ => None

  private def asInt(dv: DynamicValue): Option[Int] = asFloat(dv).map(_.toInt)

object OpenAiEmbedder:
  /** Build from `OPENAI_API_KEY` (or `envVar`), mirroring [[OpenAiLanguageModel.fromEnv]]. */
  def fromEnv(
      model: String,
      baseUrl: String = OpenAiClient.defaultBaseUrl,
      envVar: String = "OPENAI_API_KEY"
  ): Either[DspyError, OpenAiEmbedder] =
    sys.env.get(envVar) match
      case Some(value) if value.nonEmpty => Right(OpenAiEmbedder(model = model, apiKey = value, baseUrl = baseUrl))
      case _ => Left(RuntimeError("openai_config", s"Missing '$envVar' environment variable"))
