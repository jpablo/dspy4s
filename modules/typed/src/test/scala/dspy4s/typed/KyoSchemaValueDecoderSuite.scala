package dspy4s.typed

import kyo.{Json, Schema, Structure}
import munit.FunSuite

enum KyoFlatEmotion:
  case sadness, joy, love

object KyoFlatEmotion extends ValueDecoder.FlatEnum[KyoFlatEmotion]

case class KyoFlatOutput(sentiment: KyoFlatEmotion) derives Schema

case class KyoCitation(title: String, score: Double) derives Schema

case class KyoNestedOutput(
    answer: String,
    sentiment: KyoFlatEmotion,
    citations: List[KyoCitation]
) derives Schema

case class KyoNestedContainer(result: KyoNestedOutput)

class KyoSchemaValueDecoderSuite extends FunSuite:

  test("flat enum kyo-schema helper encodes case names as strings") {
    val json = Json.encode(KyoFlatOutput(KyoFlatEmotion.joy))
    assertEquals(json, """{"sentiment":"joy"}""")
  }

  test("flat enum kyo-schema helper decodes case-name strings") {
    val result = Json.decode[KyoFlatOutput]("""{"sentiment":"joy"}""")
    result.fold(
      onSuccess = out => assertEquals(out.sentiment, KyoFlatEmotion.joy),
      onFailure = e => fail(s"decode failed: ${e.getMessage}"),
      onPanic = t => fail(s"panic: $t")
    )
  }

  test("flat enum kyo-schema helper rejects unknown case-name strings") {
    val result = Json.decode[KyoFlatOutput]("""{"sentiment":"confused"}""")
    assert(result.isFailure, s"expected decode failure, got: $result")
  }

  test("schema-backed ValueDecoder decodes adapter-like nested maps") {
    val decoder = summon[ValueDecoder[KyoNestedOutput]]
    val raw = Map[String, Any](
      "answer" -> "Paris",
      "sentiment" -> "joy",
      "citations" -> List(
        Map("title" -> "Wikipedia", "score" -> 0.9),
        Map("title" -> "Britannica", "score" -> 0.8)
      )
    )

    val decoded = decoder.decode(raw)
    assertEquals(
      decoded,
      Right(KyoNestedOutput(
        answer = "Paris",
        sentiment = KyoFlatEmotion.joy,
        citations = List(KyoCitation("Wikipedia", 0.9), KyoCitation("Britannica", 0.8))
      ))
    )
  }

  test("schema-backed ValueDecoder encodes nested values to adapter-like maps") {
    val decoder = summon[ValueDecoder[KyoNestedOutput]]
    val encoded = decoder.encode(KyoNestedOutput(
      answer = "Paris",
      sentiment = KyoFlatEmotion.love,
      citations = List(KyoCitation("Wikipedia", 0.9))
    ))

    assertEquals(
      encoded,
      Map(
        "answer" -> "Paris",
        "sentiment" -> "love",
        "citations" -> List(Map("title" -> "Wikipedia", "score" -> 0.9))
      )
    )
  }

  test("schema-backed ValueDecoder normalizes nested primitive strings") {
    val decoder = summon[ValueDecoder[KyoNestedOutput]]
    val raw = Map[String, Any](
      "answer" -> "Paris",
      "sentiment" -> "joy",
      "citations" -> List(Map("title" -> "Wikipedia", "score" -> "0.9"))
    )

    assertEquals(
      decoder.decode(raw),
      Right(KyoNestedOutput(
        answer = "Paris",
        sentiment = KyoFlatEmotion.joy,
        citations = List(KyoCitation("Wikipedia", 0.9))
      ))
    )
  }

  test("case-class Shape decodes whole products through kyo-schema") {
    val shape = Shape.derived[KyoNestedContainer]
    val decoded = shape.decode(Map(
      "result" -> Map(
        "answer" -> "Paris",
        "sentiment" -> "joy",
        "citations" -> List(Map("title" -> "Wikipedia", "score" -> 0.9))
      )
    ))

    assertEquals(
      decoded,
      Right(KyoNestedContainer(KyoNestedOutput(
        answer = "Paris",
        sentiment = KyoFlatEmotion.joy,
        citations = List(KyoCitation("Wikipedia", 0.9))
      )))
    )
  }

  test("case-class Shape normalizes nested primitive strings before kyo-schema decode") {
    val shape = Shape.derived[KyoNestedContainer]
    val decoded = shape.decode(Map(
      "result" -> Map(
        "answer" -> "Paris",
        "sentiment" -> "joy",
        "citations" -> List(Map("title" -> "Wikipedia", "score" -> "0.9"))
      )
    ))

    assertEquals(
      decoded,
      Right(KyoNestedContainer(KyoNestedOutput(
        answer = "Paris",
        sentiment = KyoFlatEmotion.joy,
        citations = List(KyoCitation("Wikipedia", 0.9))
      )))
    )
  }

  test("schema-backed decoder accepts Structure.Value directly") {
    val decoder = summon[ValueDecoder[KyoNestedOutput]]
    val value = Structure.encode(KyoNestedOutput(
      answer = "Paris",
      sentiment = KyoFlatEmotion.sadness,
      citations = Nil
    ))

    assertEquals(
      decoder.decode(value),
      Right(KyoNestedOutput("Paris", KyoFlatEmotion.sadness, Nil))
    )
  }
