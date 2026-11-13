package dspy4s.harness.inspect

import dspy4s.harness.host.SessionFile
import dspy4s.harness.kernel.{Entry, Logged, Meta, Source, Taint}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import zio.blocks.schema.json.Json

import HighlightLaws.*
import InspectorLaws.{LoggedCodec, Markup, Start, entry, marked, text}

/** The laws of the colors of the Scala view and of the JSON view, and of the layout of the JSON view
  * (docs/code-harness/OBSERVABILITY.md, 5.3 and 6: L18 and L19).
  */
class HighlightLaws extends ScalaCheckSuite:

  // ── L18: the colors ──

  property("Inspect L18: the pieces of a view, joined, give its text, for any text") {
    forAllNoShrink(syntax) { t =>
      assertEquals(Highlight.json(t).map(_.text).mkString, t)
      assertEquals(Highlight.scala(t).map(_.text).mkString, t)
      assert(Highlight.json(t).forall(_.text.nonEmpty) && Highlight.scala(t).forall(_.text.nonEmpty))
    }
  }

  property("Inspect L18: the HTML of the pieces shows the text, and holds no markup from it") {
    forAllNoShrink(Gen.oneOf(marked, text, syntax)) { t =>
      Vector(Highlight.json(t), Highlight.scala(t)).foreach { tokens =>
        val html = Highlight.html(tokens)
        assertEquals(plain(html), t)
        assert(!html.contains(Markup) && !html.contains("<i data-m"), html)
      }
    }
  }

  property("Inspect L18: the JSON pieces are the keys, the strings, the numbers and the literals of the value") {
    forAllNoShrink(json(3)) { value =>
      assertEquals(colored(Highlight.json(JsonText.pretty(value))), leaves(value))
    }
  }

  property("Inspect L18: the JSON pieces of each entry are the keys and the leaves of its value") {
    forAllNoShrink(entry(text)) { value =>
      val parsed = Json.parse(LoggedCodec.encodeToString(logged(value))).fold(e => fail(e.toString), identity)
      assertEquals(colored(Highlight.json(JsonText.pretty(parsed))), leaves(parsed))
    }
  }

  property(
    "Inspect L18: in the Scala view of an entry, the case is a type, each field is a key, and a string is one piece"
  ) {
    forAllNoShrink(entry(text)) { value =>
      val tokens = Highlight.scala(ScalaText.of(value))
      assertEquals(tokens.headOption, Some(Token(Tint.Type, value.productPrefix)))
      value.productElementNames.zip(value.productIterator).foreach { (name, field) =>
        assert(tokens.contains(Token(Tint.Key, name)), s"$name is not a key in $tokens")
        val expected = field match
          case s: String  => Some(Token(Tint.Str, ScalaText.of(s)))
          case n: Int     => Some(Token(Tint.Num, n.toString))
          case n: Long    => Some(Token(Tint.Num, s"${n}L"))
          case b: Boolean => Some(Token(Tint.Lit, b.toString))
          case _          => None
        expected.foreach(token => assert(tokens.contains(token), s"$token is not in $tokens"))
      }
    }
  }

  test("Inspect L18: the Scala view and the JSON view of a row have their colors") {
    val row  = rowOf(Entry.StopRequested("FAKE-REASON"))
    val html = EntryViews.html(row)
    val view = """(?s)<pre class="v v-(scala|json)">(.*?)</pre>""".r
    val got  = view.findAllMatchIn(html).map(m => m.group(1) -> m.group(2)).toMap
    assert(got("scala").contains("""<span class="t-type">StopRequested</span>"""), got("scala"))
    assert(got("scala").contains("""<span class="t-key">reason</span>"""), got("scala"))
    assert(got("scala").contains("""<span class="t-str">&quot;FAKE-REASON&quot;</span>"""), got("scala"))
    assert(got("json").contains("""<span class="t-key">&quot;reason&quot;</span>"""), got("json"))
    assert(got("json").contains("""<span class="t-str">&quot;FAKE-REASON&quot;</span>"""), got("json"))
    assert(!got("json").contains("&quot;seq&quot;") && !got("json").contains("&quot;meta&quot;"), got("json"))
    // A case with no field is an empty object, and the layout of the view gives it as {}.
    val turn = """(?s)<pre class="v v-json">(.*?)</pre>""".r.findFirstMatchIn(EntryViews.html(rowOf(Entry.TurnStarted)))
      .map(_.group(1))
    assertEquals(
      turn,
      Some("""{
  <span class="t-key">&quot;TurnStarted&quot;</span>: {}
}""")
    )
  }

  // ── L19: the layout of the JSON view ──

  property("Inspect L19: the layout gives the same value, with no blank line") {
    forAllNoShrink(json(3)) { value =>
      val laid = JsonText.pretty(value)
      assertEquals(Json.parse(laid).map(_.print), Right(value.print))
      assert(laid.linesIterator.forall(_.trim.nonEmpty), laid)
    }
  }

  test("Inspect L19: an empty object is {} and an empty array is [], also inside a value") {
    assertEquals(JsonText.pretty(Json.Object()), "{}")
    assertEquals(JsonText.pretty(Json.Array()), "[]")
    assertEquals(
      JsonText.pretty(Json.Object("a" -> Json.Object(), "b" -> Json.Array(Json.Number(1), Json.Array()))),
      "{\n  \"a\": {},\n  \"b\": [\n    1,\n    []\n  ]\n}"
    )
  }

object HighlightLaws:

  /** A text with many of the marks that a lexer reads: quotes, escapes, brackets, numbers, words and line ends. */
  val syntax: Gen[String] = Gen.listOf(Gen.frequency(
    4 -> Gen.oneOf("\"", "\\", "'", "{", "}", "[", "]", ":", ",", "=", "->", "(", ")", "-", ".", "\"\"\"", " ", "\n"),
    3 -> Gen.oneOf("a", "Z", "true", "null", "x1", "_", "$", "0", "12", "3.5", "1e-4", "41L", "é", "日", "\u0001")
  )).map(_.mkString)

  val key: Gen[String] = Gen.oneOf(
    Gen.alphaStr.map(_.take(6)),
    Gen.const("a\"b:c"),
    Gen.const("{x}"),
    Gen.const(""),
    Gen.const("line\nend"),
    Gen.const(Markup)
  )

  /** A JSON value with at most `depth` levels of objects and arrays. */
  def json(depth: Int): Gen[Json] =
    val leaf = Gen.oneOf(
      text.map(Json.String(_)),
      Gen.choose(-1000L, 1000L).map(Json.Number(_)),
      Gen.choose(-1e6, 1e6).map(Json.Number(_)),
      Gen.oneOf("1.5E-7", "-2.5E-9", "6.02E+23").map(n => Json.Number(BigDecimal(n))),
      Gen.oneOf(true, false).map(Json.Boolean(_)),
      Gen.const(Json.Null)
    )
    if depth <= 0 then leaf
    else
      Gen.frequency(
        3 -> leaf,
        2 -> Gen.choose(0, 3).flatMap(n => Gen.listOfN(n, Gen.zip(key, json(depth - 1))))
          .map(fields => Json.Object(fields.distinctBy(_._1)*)),
        2 -> Gen.choose(0, 3).flatMap(n => Gen.listOfN(n, json(depth - 1))).map(items => Json.Array(items*))
      )

  /** The keys and the leaves of `value` in the order of its text, each with the kind of its color. */
  def leaves(value: Json): Vector[Token] =
    value match
      case o: Json.Object => o.value.toVector.flatMap((k, v) => Token(Tint.Key, Json.String(k).print) +: leaves(v))
      case a: Json.Array  => a.value.toVector.flatMap(leaves)
      case s: Json.String => Vector(Token(Tint.Str, s.print))
      case n: Json.Number => Vector(Token(Tint.Num, n.print))
      case other          => Vector(Token(Tint.Lit, other.print))

  /** The pieces of `tokens` that have a color other than the color of the marks. */
  def colored(tokens: Vector[Token]): Vector[Token] = tokens.filter(t => t.tint != Tint.Plain && t.tint != Tint.Punct)

  /** The text that a browser shows for the HTML of the pieces: the spans go, and each entity is its mark again. */
  def plain(html: String): String =
    html.replaceAll("""<span class="t-[a-z]+">""", "").replace("</span>", "").replace("&lt;", "<").replace("&gt;", ">")
      .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

  def logged(value: Entry): Logged = Logged(7L, Meta(Start, Source.Harness, Taint.Trusted, required = true), value)

  def rowOf(value: Entry): SessionFile.Row =
    val l = logged(value)
    SessionFile.Row(7L, true, LoggedCodec.encodeToString(l), Right(l))
