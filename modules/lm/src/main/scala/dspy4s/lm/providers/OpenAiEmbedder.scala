package dspy4s.lm.providers

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.updated
import dspy4s.lm.contracts.Embedder
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonCodec
import zio.blocks.schema.json.JsonCodecDeriver

/** The OpenAI embeddings provider (`POST /embeddings`): the part of `dspy.Embedder` for a model on a server (G-10). The
  * embedder puts `batchSize` texts at most in each request (upstream `batch_size`, default 200), and joins the rows of
  * the replies. The rows of a reply are in the order of their `index` field. `options` holds more request fields, for
  * example `dimensions`, and each request body has them.
  *
  * @param client
  *   the HTTP client with the API key, the base URL, and the transport. The embedder sends each batch with
  *   [[OpenAiClient.post]], so a failure gets the same LM error as a failure of a model call.
  */
final case class OpenAiEmbedder(
    model             : String,
    client            : OpenAiClient,
    batchSize         : BatchSize           = BatchSize(200),
    options           : DynamicValue.Record = DynamicValue.Record.empty,
    embeddingsEndpoint: String              = "/embeddings"
) extends Embedder:

  override def id: String = model

  /** Close the client (see [[OpenAiClient.close]]). The embedder stays usable: a later call opens new connections. */
  def close(): Unit = client.close()

  override def embed(texts: Vector[String])(using RuntimeContext): Either[DspyError, Vector[Vector[Float]]] =
    if texts.isEmpty then Right(Vector.empty)
    else
      texts.grouped(batchSize).foldLeft[Either[DspyError, Vector[Vector[Float]]]](Right(Vector.empty)) {
        (acc, batch) => acc.flatMap(rows => embedBatch(batch).map(rows ++ _))
      }

  private def embedBatch(batch: Seq[String]): Either[DspyError, Vector[Vector[Float]]] =
    val payload = options
      .updated(WireKeys.model, DynamicValues.string(model))
      .updated(WireKeys.input, DynamicValues.sequence(batch.map(DynamicValues.string)))
    client.post(embeddingsEndpoint, payload, Some(model)).flatMap(OpenAiEmbedder.rows(_, expected = batch.size))

object OpenAiEmbedder:

  /** One row of the `data` array of a reply. */
  private final case class EmbeddingRow(index: Int, embedding: Vector[Float]) derives Schema

  /** The reply of the embeddings endpoint. The codec ignores the other keys (`object`, `model`, `usage`). */
  private final case class EmbeddingsReply(data: Vector[EmbeddingRow] = Vector.empty) derives Schema

  /** The lenient codec of a reply (unknown keys are ignored, and an absent `data` gives no row). */
  private val replyCodec: JsonCodec[EmbeddingsReply] = Schema[EmbeddingsReply].derive(JsonCodecDeriver)

  /** The rows of the reply `body`, in the order of their `index`: the reply can give the rows in another order. A reply
    * that the codec cannot read, for example one with a row with no `index` or with an `embedding` that is not an array
    * of numbers, is a `ParseError` with the error of the codec. A reply with a count of rows other than `expected` is a
    * `ParseError` too. So is a reply whose indexes are not exactly 0 to `expected - 1`, for example with a duplicate
    * index: such a reply cannot give one row for each text.
    */
  private def rows(body: String, expected: Int): Either[DspyError, Vector[Vector[Float]]] =
    replyCodec.decode(body) match
      case Left(error)  => Left(ParseError("openai_embeddings", s"Invalid embeddings reply: ${error.message.take(200)}"))
      case Right(reply) =>
        val sorted  = reply.data.sortBy(_.index)
        val indexes = sorted.map(_.index)
        if sorted.size != expected then
          Left(ParseError("openai_embeddings", s"Expected $expected embedding rows, got ${sorted.size}"))
        else if indexes != Vector.range(0, expected) then
          Left(ParseError(
            "openai_embeddings",
            s"Expected the embedding indexes 0 to ${expected - 1}, got ${indexes.mkString(", ")}"
          ))
        else Right(sorted.map(_.embedding))

  /** An embedder with the API key from the environment variable `envVar` (by default `OPENAI_API_KEY`), as
    * [[OpenAiLanguageModel.fromEnv]]. `baseUrl` selects the OpenAI-compatible server. An `envVar` that is absent or
    * empty gives [[dspy4s.core.contracts.LmNotConfiguredError]].
    */
  def fromEnv(
      model  : String,
      baseUrl: String = OpenAiClient.defaultBaseUrl,
      envVar : String = "OPENAI_API_KEY"
  ): Either[DspyError, OpenAiEmbedder] =
    OpenAiClient.fromEnv(base = baseUrl, envVar = envVar).map(client => OpenAiEmbedder(model = model, client = client))

  /** An embedder for a local OpenAI-compatible server that does not check credentials (for example Ollama at
    * `http://localhost:11434/v1`), as [[OpenAiLanguageModel.local]].
    */
  def local(model: String, baseUrl: String, apiKey: String = "local"): OpenAiEmbedder =
    OpenAiEmbedder(model = model, client = OpenAiClient(apiKey = apiKey, baseUrl = baseUrl))
