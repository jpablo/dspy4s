package dspy4s.harness.inspect

import dspy4s.core.runtime.RuntimeEnvironment
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import zio.blocks.schema.json.Json

import StreamJsonLaws.*

/** The laws of the reply of a stream put together from its chunks (docs/code-harness/OBSERVABILITY.md, 5.4 and 6: L21).
  */
class StreamJsonLaws extends ScalaCheckSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  property("Inspect L21: a completion cut into the chunks of a stream and merged gives the completion again") {
    forAllNoShrink(completions.flatMap(c => streamOf(c).map(c -> _))) { case (completion, (lines, tokens)) =>
      assertEquals(StreamJson.merge(lines).map(canon), Some(canon(expected(completion, tokens))))
    }
  }

  property("Inspect L21: lines that are not data chunks change nothing") {
    val noise = Gen.oneOf(": keep-alive", "event: message", "data: not json", "data: [1, 2]", "", "data: [DONE]")
    forAllNoShrink(completions.flatMap(streamOf).map(_._1), Gen.listOf(Gen.zip(Gen.choose(0, 40), noise))) {
      (lines, extra) =>
        val noisy = extra.foldLeft(lines)((all, add) => all.patch(add._1.min(all.size), Vector(add._2), 0))
        assertEquals(StreamJson.merge(noisy).map(canon), StreamJson.merge(lines).map(canon))
    }
  }

  test("Inspect L21: a role that each delta repeats comes once, and a content that is null in each delta stays null") {
    val chunk  = (delta: String) => s"""data: {"choices":[{"index":0,"delta":$delta}]}"""
    val call   = """{"index":0,"id":"c1","function":{"name":"read","arguments":"{}"}}"""
    val merged = StreamJson.merge(Vector(
      chunk("""{"role":"assistant","content":null}"""),
      chunk(s"""{"role":"assistant","tool_calls":[$call]}""")
    ))
    assertEquals(
      merged.map(_.print),
      Some(
        """{"choices":[{"index":0,"message":{"role":"assistant","content":null,""" +
          """"tool_calls":[{"id":"c1","function":{"name":"read","arguments":"{}"}}]}}]}"""
      )
    )
  }

  test("Inspect L21: a stream with no chunk gives no reply") {
    assertEquals(StreamJson.merge(Vector.empty), None)
    assertEquals(StreamJson.merge(Vector("", ": keep-alive", "data: [DONE]")), None)
  }

  property("Inspect L21: the merged reply agrees with the reading of the model") {
    forAllNoShrink(completions.flatMap(c => streamOf(c).map(c -> _._1))) { (completion, lines) =>
      val reading = Chat.read(exchangeOf(lines))
      assertEquals(reading.error, None)
      val output = reading.output.getOrElse(fail("the model read no output"))
      assertEquals(output.text, completion.content)
      assertEquals(output.reasoning, completion.reasoning)
      assertEquals(output.toolCalls.map(c => (c.id, c.name)), completion.calls.map(c => (Some(c.id), c.name)))
      assertEquals(reading.finish, Some(completion.finish))
      assertEquals(reading.usage.map(u => (u.promptTokens, u.completionTokens)), Some(completion.usage))
    }
  }

object StreamJsonLaws:

  final case class Call(id: String, name: String, arguments: String)

  final case class Completion(
      id          : String,
      model       : String,
      created     : Long,
      content     : String,
      reasoning   : String,
      reasoningKey: String,
      calls       : Vector[Call],
      finish      : String,
      usage       : (Long, Long),
      logprobs    : Boolean,
      usageEach   : Boolean
  )

  private val piece: Gen[String] = Gen.oneOf(
    Gen.alphaNumStr.map(_.take(6)),
    Gen.const(" "),
    Gen.const("\n"),
    Gen.const("\"q\""),
    Gen.const("{\"a\":"),
    Gen.const("é日")
  )

  private val words: Gen[String] = Gen.choose(0, 6).flatMap(n => Gen.listOfN(n, piece)).map(_.mkString)

  private val arguments: Gen[String] =
    Gen.alphaNumStr.map(v => Json.Object("path" -> Json.String(v.take(8)), "n" -> Json.Number(3)).print)

  val completions: Gen[Completion] =
    for
      id           <- Gen.alphaNumStr.map(s => s"chatcmpl-fake-${s.take(6)}")
      model        <- Gen.oneOf("fake-model", "fake-model-2")
      created      <- Gen.choose(1_700_000_000L, 1_800_000_000L)
      content      <- words
      reasoning    <- words
      reasoningKey <- Gen.oneOf("reasoning", "reasoning_content")
      n            <- Gen.choose(0, 2)
      calls        <- Gen.listOfN(
                 n,
                 for i <- Gen.alphaNumStr; name <- Gen.oneOf("read", "write"); a <- arguments
                 yield Call(s"call-${i.take(4)}", name, a)
               )
      finish   <- Gen.oneOf("stop", "length", "tool_calls")
      prompt   <- Gen.choose(1L, 9000L)
      done     <- Gen.choose(1L, 900L)
      logprobs <- Gen.oneOf(true, false)
      each     <- Gen.oneOf(true, false)
    yield Completion(
      id,
      model,
      created,
      content,
      reasoning,
      reasoningKey,
      calls.toVector.zipWithIndex.map((c, i) => c.copy(id = s"${c.id}-$i")),
      finish,
      (prompt, done),
      logprobs,
      each
    )

  /** The pieces of `text`, cut at random places. The pieces, joined, give `text`. */
  private def cut(text: String): Gen[Vector[String]] =
    Gen.listOfN(3, Gen.choose(0, text.length)).map { at =>
      val stops = (0 +: at.sorted :+ text.length).distinct
      stops.zip(stops.drop(1)).map((a, b) => text.substring(a, b)).toVector
    }

  /** The lines of a stream of `c`, as an OpenAI-compatible endpoint sends them: a chunk with the role, the pieces of
    * the reasoning, of the text and of the arguments of each tool call, and a chunk with the finish reason. Each chunk
    * is a `data:` line with an empty line after it, and the last line is `data: [DONE]`. It also gives the pieces of
    * the reasoning and of the text in their order, the tokens of the `logprobs` of their chunks.
    */
  def streamOf(c: Completion): Gen[(Vector[String], Vector[String])] =
    for
      reasoning <- if c.reasoning.isEmpty then Gen.const(Vector.empty) else cut(c.reasoning)
      content   <- cut(c.content)
      calls     <- Gen.sequence[Vector[Vector[String]], Vector[String]](c.calls.map(call => cut(call.arguments)))
    yield
      val deltas: Vector[(Json, Option[String])] =
        Vector(Json.Object("role" -> Json.String("assistant"), "content" -> Json.String("")) -> None) ++
          reasoning.map(p => Json.Object(c.reasoningKey -> Json.String(p)) -> Some(p)) ++
          content.map(p => Json.Object("content" -> Json.String(p)) -> Some(p)) ++
          c.calls.zip(calls).zipWithIndex.flatMap { case ((call, parts), i) =>
            val start = Json.Object("tool_calls" -> Json.Array(Json.Object(
              "index"    -> Json.Number(i),
              "id"       -> Json.String(call.id),
              "type"     -> Json.String("function"),
              "function" -> Json.Object("name" -> Json.String(call.name), "arguments" -> Json.String(""))
            )))
            (start -> None) +: parts.map { p =>
              Json.Object("tool_calls" -> Json.Array(Json.Object(
                "index"    -> Json.Number(i),
                "function" -> Json.Object("arguments" -> Json.String(p))
              ))) -> None
            }
          }
      val all    = deltas :+ (Json.Object() -> None)
      val chunks = all.zipWithIndex.map { case ((delta, token), i) =>
        val last   = i == all.size - 1
        val choice = Json.Object(
          Vector("index" -> Json.Number(0), "delta" -> delta) ++
            Option.when(c.logprobs)("logprobs" -> logprobsOf(token)).toVector ++
            Vector("finish_reason" -> (if last then Json.String(c.finish) else Json.Null))*
        )
        val usage = Option.when(last || c.usageEach)(usageOf(c, if last then c.usage._2 else i.toLong))
        Json.Object(
          Vector(
            "id"      -> Json.String(c.id),
            "object"  -> Json.String("chat.completion.chunk"),
            "created" -> Json.Number(c.created),
            "model"   -> Json.String(c.model),
            "choices" -> Json.Array(choice)
          ) ++ usage.map("usage" -> _).toVector*
        )
      }
      (chunks.flatMap(chunk => Vector(s"data: ${chunk.print}", "")) :+ "data: [DONE]", reasoning ++ content)

  private def logprobsOf(token: Option[String]): Json =
    token.fold(Json.Null)(t =>
      Json.Object("content" -> Json.Array(Json.Object("token" -> Json.String(t), "logprob" -> Json.Number(-0.5))))
    )

  private def usageOf(c: Completion, done: Long): Json =
    Json.Object(
      "prompt_tokens"     -> Json.Number(c.usage._1),
      "total_tokens"      -> Json.Number(c.usage._1 + done),
      "completion_tokens" -> Json.Number(done)
    )

  /** The reply with no stream that the chunks of `c` stand for, with `tokens` as the tokens of its `logprobs`. */
  def expected(c: Completion, tokens: Vector[String]): Json =
    val message = Vector("role" -> Json.String("assistant"), "content" -> Json.String(c.content)) ++
      Option.when(c.reasoning.nonEmpty)(c.reasoningKey -> Json.String(c.reasoning)).toVector ++
      Option.when(c.calls.nonEmpty)("tool_calls" -> Json.Array(c.calls.map(call =>
        Json.Object(
          "id"       -> Json.String(call.id),
          "type"     -> Json.String("function"),
          "function" -> Json.Object("name" -> Json.String(call.name), "arguments" -> Json.String(call.arguments))
        )
      )*)).toVector
    val items  = tokens.map(t => Json.Object("token" -> Json.String(t), "logprob" -> Json.Number(-0.5)))
    val choice = Vector("index" -> Json.Number(0), "message" -> Json.Object(message*)) ++
      Option.when(c.logprobs && tokens.nonEmpty)("logprobs" -> Json.Object("content" -> Json.Array(items*))).toVector ++
      Vector("finish_reason" -> Json.String(c.finish))
    Json.Object(
      "id"      -> Json.String(c.id),
      "object"  -> Json.String("chat.completion"),
      "created" -> Json.Number(c.created),
      "model"   -> Json.String(c.model),
      "choices" -> Json.Array(Json.Object(choice*)),
      "usage"   -> usageOf(c, c.usage._2)
    )

  /** `json` with the keys of each object in their order of letters, so two values compare with no regard to the order
    * of their keys.
    */
  def canon(json: Json): String =
    def sorted(value: Json): Json =
      value match
        case o: Json.Object => Json.Object(o.value.toVector.sortBy(_._1).map((k, v) => k -> sorted(v))*)
        case a: Json.Array  => Json.Array(a.value.toVector.map(sorted)*)
        case other          => other
    sorted(json).print

  /** An exchange of a stream with `lines` as its reply. */
  def exchangeOf(lines: Vector[String]): Exchange =
    val request = Json.Object(
      "kind"   -> Json.String("request"),
      "id"     -> Json.String("x1"),
      "url"    -> Json.String("http://test.invalid/v1/chat/completions"),
      "stream" -> Json.Boolean(true),
      "body"   -> Json.String("""{"model":"fake-model","messages":[],"stream":true}""")
    )
    val reply = Json.Object(
      "kind"   -> Json.String("reply"),
      "id"     -> Json.String("x1"),
      "status" -> Json.Number(200),
      "lines"  -> Json.Array(lines.map(Json.String(_))*),
      "end"    -> Json.String("closed")
    )
    Exchange(0, request, Some(reply))
