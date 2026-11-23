package dspy4s.harness.inspect

import dspy4s.harness.host.SessionFile
import dspy4s.harness.kernel.{Entry, Logged, Meta, Source, Taint}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import zio.blocks.schema.json.Json

import HighlightLaws.*
import InspectorLaws.{LoggedCodec, Markup, Start, entry, marked, text}

/** The laws of the colors of the Scala view and of the JSON view, of the layout of the JSON view, and of the colors of
  * Scala source (docs/code-harness/OBSERVABILITY.md, 5.3 and 6: L18, L19 and L26).
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

  // ── L20: the code view of a body ──

  property("Inspect L20: the code view of the layout of a value gives the layout again, with a number for each line") {
    forAllNoShrink(json(3)) { value =>
      val laid = JsonText.pretty(value)
      val html = Highlight.code(laid.linesIterator.toVector)
      val rows = codeRows(html)
      assert(html.startsWith("""<div class="code">"""), html)
      assertEquals("""class="ln"""".r.findAllMatchIn(html).size, rows.size)
      assertEquals(rows.map(_._1), (1 to rows.size).toVector)
      assertEquals(rows.map((_, indent, piece) => " " * indent + plain(piece)).mkString("\n"), laid)
    }
  }

  property(
    "Inspect L20: the code view keeps each line of any text with its first spaces, and holds no markup from it"
  ) {
    forAllNoShrink(Gen.oneOf(syntax, marked, text)) { t =>
      val lines = t.split("\n", -1).toVector
      val html  = Highlight.code(lines)
      assertEquals(codeRows(html).map((_, indent, piece) => " " * indent + plain(piece)), lines)
      assert(!html.contains(Markup) && !html.contains("<i data-m"), html)
    }
  }

  test("Inspect L20: the code view has the colors of JSON, and a long line wraps under its own indent") {
    val html = Highlight.code(Vector("{", """  "k": "v",""", "    1"))
    assert(html.contains("""<span class="c" style="--i:2"><span class="t-key">&quot;k&quot;</span>: """), html)
    assert(html.contains("""<span class="c" style="--i:4"><span class="t-num">1</span></span>"""), html)
  }

  // ── L26: the colors of Scala source ──

  property("Inspect L26: the pieces of Scala source, joined, give the text, and the HTML shows it with no markup") {
    forAllNoShrink(Gen.oneOf(scalaSource, syntax, marked, text)) { t =>
      val tokens = Highlight.source(t)
      assertEquals(tokens.map(_.text).mkString, t)
      assert(tokens.forall(_.text.nonEmpty), tokens.toString)
      val html = Highlight.sourceHtml(t)
      assertEquals(plain(html), t)
      assert(!html.contains(Markup) && !html.contains("<i data-m"), html)
    }
  }

  property("Inspect L26: a line comment and a string with one quote end at the line end") {
    forAllNoShrink(scalaSource) { t =>
      Highlight.source(t).foreach { token =>
        val line = (token.tint == Tint.Comment && token.text.startsWith("//")) ||
          (token.tint == Tint.Str && !token.text.contains("\"\"\""))
        assert(!line || !token.text.contains('\n'), s"$token in $t")
      }
    }
  }

  test("Inspect L26: the keywords, the names of the definitions, the types, the annotations and the numbers") {
    val src = "@assumeSafe trait Files extends Cap:\n  def read(path: String)(using f: Files): String\n" +
      "  update def write(p: String, n: Int = 1_000): Unit"
    assertEquals(
      colored(Highlight.source(src)),
      Vector(
        Token(Tint.Annotation, "@assumeSafe"),
        Token(Tint.Keyword, "trait"),
        Token(Tint.Type, "Files"),
        Token(Tint.Keyword, "extends"),
        Token(Tint.Type, "Cap"),
        Token(Tint.Keyword, "def"),
        Token(Tint.Def, "read"),
        Token(Tint.Type, "String"),
        Token(Tint.Keyword, "using"),
        Token(Tint.Type, "Files"),
        Token(Tint.Type, "String"),
        Token(Tint.Keyword, "update"),
        Token(Tint.Keyword, "def"),
        Token(Tint.Def, "write"),
        Token(Tint.Type, "String"),
        Token(Tint.Type, "Int"),
        Token(Tint.Num, "1_000"),
        Token(Tint.Type, "Unit")
      )
    )
    assertEquals(
      colored(Highlight.source("0x1F + 1.5e-3 + 41L + 1.max(2) + x1 + true + null + `type` + a @ b")),
      Vector(
        Token(Tint.Num, "0x1F"),
        Token(Tint.Num, "1.5e-3"),
        Token(Tint.Num, "41L"),
        Token(Tint.Num, "1"),
        Token(Tint.Num, "2"),
        Token(Tint.Lit, "true"),
        Token(Tint.Lit, "null")
      )
    )
  }

  test("Inspect L26: a comment is one piece, and a block comment can hold another one") {
    val src = "// a \"line\" comment\nval x = 1 /* a /* nested */ block */ + 2\n/** doc\n  * `code`\n  */\ndef y = 'c'"
    assertEquals(
      colored(Highlight.source(src)),
      Vector(
        Token(Tint.Comment, "// a \"line\" comment"),
        Token(Tint.Keyword, "val"),
        Token(Tint.Def, "x"),
        Token(Tint.Num, "1"),
        Token(Tint.Comment, "/* a /* nested */ block */"),
        Token(Tint.Num, "2"),
        Token(Tint.Comment, "/** doc\n  * `code`\n  */"),
        Token(Tint.Keyword, "def"),
        Token(Tint.Def, "y"),
        Token(Tint.Str, "'c'")
      )
    )
  }

  test("Inspect L26: the strings, with an interpolation, a string with no end, a character and a block") {
    val src =
      "val a = s\"x ${f(\"y\")} \\\" z\" + \"open\nval b = '\\n' + '{ q }\n\"\"\"multi\n\"line\"\"\"\" + raw\"w\""
    assertEquals(
      colored(Highlight.source(src)),
      Vector(
        Token(Tint.Keyword, "val"),
        Token(Tint.Def, "a"),
        Token(Tint.Str, "s\"x ${f(\"y\")} \\\" z\""),
        Token(Tint.Str, "\"open"),
        Token(Tint.Keyword, "val"),
        Token(Tint.Def, "b"),
        Token(Tint.Str, "'\\n'"),
        Token(Tint.Str, "\"\"\"multi\n\"line\"\"\"\""),
        Token(Tint.Str, "raw\"w\"")
      )
    )
  }

  test("Inspect L26: a soft keyword is a keyword only in its place") {
    val src = "inline def f = m.update(k)\nval end = open\nend match\nopaque type T = Int\nx.open()\n" +
      "extension (s: String) def n = s.as[Int]\nimport a.{b as c}\nenum E derives CanEqual\nval k = s.end min f.extension"
    assertEquals(
      colored(Highlight.source(src)),
      Vector(
        Token(Tint.Keyword, "inline"),
        Token(Tint.Keyword, "def"),
        Token(Tint.Def, "f"),
        Token(Tint.Keyword, "val"),
        Token(Tint.Def, "end"),
        Token(Tint.Keyword, "end"),
        Token(Tint.Keyword, "match"),
        Token(Tint.Keyword, "opaque"),
        Token(Tint.Keyword, "type"),
        Token(Tint.Type, "T"),
        Token(Tint.Type, "Int"),
        Token(Tint.Keyword, "extension"),
        Token(Tint.Type, "String"),
        Token(Tint.Keyword, "def"),
        Token(Tint.Def, "n"),
        Token(Tint.Type, "Int"),
        Token(Tint.Keyword, "import"),
        Token(Tint.Keyword, "as"),
        Token(Tint.Keyword, "enum"),
        Token(Tint.Type, "E"),
        Token(Tint.Keyword, "derives"),
        Token(Tint.Type, "CanEqual"),
        Token(Tint.Keyword, "val"),
        Token(Tint.Def, "k")
      )
    )
  }

  test("Inspect L26: the HTML of Scala source has a class for each color") {
    assertEquals(
      Highlight.sourceHtml("@a def f = \"<b>\" // c"),
      """<span class="t-annotation">@a</span> <span class="t-keyword">def</span> <span class="t-def">f</span> = """ +
        """<span class="t-str">&quot;&lt;b&gt;&quot;</span> <span class="t-comment">// c</span>"""
    )
    val card = EntryViews.card(logged(Entry.Acted("FAKE-REASONING", "FAKE-TEXT", Some("val n = 1"))))
    assert(
      card.contains(
        """<pre><code><span class="t-keyword">val</span> <span class="t-def">n</span> = <span class="t-num">1</span>""" +
          "</code></pre>"
      ),
      card
    )
  }

object HighlightLaws:

  /** The rows of a code view: the number, the indent and the HTML of the rest of each line. */
  def codeRows(html: String): Vector[(Int, Int, String)] =
    """<div class="ln"><span class="n">(\d+)</span><span class="c" style="--i:(\d+)">(.*?)</span></div>""".r
      .findAllMatchIn(html).map(m => (m.group(1).toInt, m.group(2).toInt, m.group(3))).toVector

  /** A Scala text with the marks of the lexer of Scala source: comments, quotes, escapes, names and numbers. */
  val scalaSource: Gen[String] = Gen.listOf(Gen.frequency(
    4 -> Gen.oneOf(
      "//",
      "/*",
      "*/",
      "/**",
      "*",
      "/",
      "\"",
      "\"\"\"",
      "'",
      "\\",
      "`",
      "@",
      "$",
      "${",
      "{",
      "}",
      "(",
      ")",
      ".",
      ":",
      "=",
      " ",
      "\n",
      "\t"
    ),
    3 -> Gen.oneOf(
      "s",
      "x",
      "Foo",
      "def",
      "val",
      "update",
      "end",
      "inline",
      "opaque",
      "type",
      "match",
      "as",
      "true",
      "1",
      "0x1F",
      "1.5e-3",
      "41L",
      "1_000",
      "é",
      "日",
      "\u0001"
    )
  )).map(_.mkString)

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
