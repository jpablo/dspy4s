package dspy4s.harness

import dspy4s.harness.kernel.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import zio.blocks.chunk.Chunk
import zio.blocks.schema.json.Json

/** The law Secret of 4.7, and the mask of 5.6. Each value is fake. */
class SecretLaws extends ScalaCheckSuite:

  private val names = Vector("FAKE_API_KEY", "FAKE_TOKEN", "FAKE_DB_PASSWORD", "FAKE_SECRET")

  private val values: Gen[String] =
    Gen.choose(Secrets.minLength, 24).flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString))

  /** The known values of a process with some secret variables, as `Secrets.known` finds them. */
  private val secrets: Gen[Secrets] =
    Gen.listOfN(names.size, values).map(vs => Secrets.known(names.zip(vs).toMap, Map.empty))

  /** A text with some known values in it, and other text around them. */
  private def texts(known: Secrets): Gen[String] =
    val piece = Gen.frequency(
      3 -> Gen.alphaNumStr,
      1 -> Gen.const(" "),
      2 -> (if known.values.isEmpty then Gen.alphaNumStr else Gen.oneOf(known.values.values.toSeq))
    )
    Gen.listOf(piece).map(_.mkString)

  private val maskedTexts: Gen[(Secrets, String)] = secrets.flatMap(s => texts(s).map((s, _)))

  property("Secret: no known value stays in a masked text") {
    forAll(maskedTexts) { (known, text) =>
      val masked = known.maskText(text)
      known.values.values.foreach(value => assert(!masked.contains(value), s"$value stays in $masked"))
    }
  }

  property("Secret: a second mask changes nothing") {
    forAll(maskedTexts) { (known, text) =>
      val once = known.maskText(text)
      assertEquals(known.maskText(once), once)
    }
  }

  test("Secret: a longer value goes first, so a value inside it does not cut it") {
    val known = Secrets(Map("SHORT" -> "abcdefgh", "LONG" -> "abcdefghijkl"))
    assertEquals(known.maskText("x abcdefghijkl y abcdefgh"), "x «secret:LONG» y «secret:SHORT»")
  }

  test("Secret: the mask reaches each string of an entry, also each key, and each string in a Json") {
    val v     = "fake-secret-value-2"
    val known = Secrets(Map("FAKE_KEY" -> v))
    val m     = "«secret:FAKE_KEY»"
    val json  = Json.Object(Chunk((v, Json.Array(Chunk(Json.String(s"a $v"), Json.Boolean(true))))))
    val clean = Json.Object(Chunk((m, Json.Array(Chunk(Json.String(s"a $m"), Json.Boolean(true))))))
    assertEquals(
      known.mask(Entry.Input(RequestId("r"), s"say $v", Map(v -> json))),
      Entry.Input(RequestId("r"), s"say $m", Map(m -> clean))
    )
    assertEquals(known.mask(Entry.Acted(v, v, Some(v))), Entry.Acted(m, m, Some(m)))
    assertEquals(
      known.mask(Entry.EffectStarted(EffectId(1, 0), "exec", Json.String(v))),
      Entry.EffectStarted(EffectId(1, 0), "exec", Json.String(m))
    )
    assertEquals(
      known.mask(Entry.EffectDone(EffectId(1, 0), EffectResult.Value(json, v))),
      Entry.EffectDone(EffectId(1, 0), EffectResult.Value(clean, m))
    )
    assertEquals(
      known.mask(Entry.Observed(v, CellOutcome.Failed(v), Map(v -> json))),
      Entry.Observed(m, CellOutcome.Failed(m), Map(m -> clean))
    )
    assertEquals(known.mask(Entry.TurnEnded(TurnOutcome.Replied(v))), Entry.TurnEnded(TurnOutcome.Replied(m)))
  }

  test("Secret: known takes the secret-like variables and the headers, and not the others or the short values") {
    val env = Map(
      "FAKE_API_KEY" -> "fake-key-12345678",
      "PATH"         -> "/usr/bin:/bin:/usr/local/bin",
      "FAKE_TOKEN"   -> "abc",
      "EDITOR"       -> "fake-editor-name"
    )
    val known = Secrets.known(env, Map("Authorization" -> "Bearer fake-header-value-1"))
    assertEquals(
      known.values,
      Map("FAKE_API_KEY" -> "fake-key-12345678", "Authorization" -> "Bearer fake-header-value-1")
    )
  }

  test("Secret: a value that is a part of a mask is not a known value") {
    // A second mask would change the first one, which names FAKE_VERY_LONG_TOKEN.
    val known = Secrets.known(Map("FAKE_VERY_LONG_TOKEN" -> "VERY_LONG_TOKEN"), Map.empty)
    assertEquals(known.values, Map.empty)
  }
