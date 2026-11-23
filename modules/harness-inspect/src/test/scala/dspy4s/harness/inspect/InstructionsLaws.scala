package dspy4s.harness.inspect

import dspy4s.harness.PolicyAdapter
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink

import InspectorLaws.{Markup, text}

/** The laws of the parts of the instructions of a chat adapter (docs/code-harness/OBSERVABILITY.md, 5.8 and L27). */
class InstructionsLaws extends ScalaCheckSuite:

  // ── Helpers ──

  private val Heads = Vector(
    "Your input fields are:",
    "Your output fields are:",
    "All interactions will be structured in the following way, with the appropriate values filled in.",
    "In adhering to this structure, your objective is:"
  )

  /** The numbered list of `fields`, as `PromptText.descriptions` writes it. */
  private def list(fields: Vector[FieldText]): String =
    fields.zipWithIndex.map((f, i) => s"${i + 1}. `${f.name}` (${f.kind}): ${f.description}").mkString("\n").strip

  /** A system message of the chat adapter, as `PromptText.systemMessage` writes it, and then `rest`. */
  private def system(
      inputs   : Vector[FieldText],
      outputs  : Vector[FieldText],
      notes    : Map[String, String],
      objective: Vector[String],
      rest     : String
  ): String =
    val holders = inputs.map(f => s"[[ ## ${f.name} ## ]]\n{${f.name}}") ++ outputs.map(f =>
      s"[[ ## ${f.name} ## ]]\n{${f.name}}" +
        notes.get(f.name).fold("")(n => s"        # note: the value you produce $n")
    )
    s"${Heads(0)}\n${list(inputs)}\n${Heads(1)}\n${list(outputs)}\n${Heads(2)}\n\n${holders.mkString("\n\n")}\n\n" +
      s"[[ ## completed ## ]]\n${Heads(3)} " + objective.map("\n        " + _).mkString +
      (if rest.isEmpty then "" else s"\n\n$rest")

  /** `lines` with no blank lines at the start and at the end, as one text. */
  private def trimmed(lines: Vector[String]): String =
    lines.dropWhile(_.isBlank).reverse.dropWhile(_.isBlank).reverse.mkString("\n")

  private val words: Gen[String] = Gen.listOfN(
    3,
    Gen.oneOf("the", "file", "`code`", "**b**", "x: y", "(z)", "a_b", Markup, "<i>")
  ).map(_.mkString(" "))

  private val description: Gen[String] = Gen.oneOf(words, words.map(w => s"$w\nConstraints: $w"), Gen.const(""))

  private def field(name: String): Gen[FieldText] =
    for kind <- Gen.oneOf("str", "int", "bool", "History", "list[str]", "dict"); d <- description
    yield FieldText(name, kind, d)

  private final case class Message(
      inputs   : Vector[FieldText],
      outputs  : Vector[FieldText],
      notes    : Map[String, String],
      objective: Vector[String],
      rest     : String
  ):
    def text: String = system(inputs, outputs, notes, objective, rest)

    def parts: Instructions =
      val markers = inputs.map(f => f.name -> s"{${f.name}}") ++ outputs.map(f =>
        f.name -> (s"{${f.name}}" + notes.get(f.name).fold("")(n => s"        # note: the value you produce $n"))
      ) :+ ("completed" -> "")
      Instructions(inputs, outputs, markers, trimmed(objective), rest)

  private val messages: Gen[Message] =
    val pool = Vector("task", "apiReference", "memory", "status", "observation", "history", "text", "code", "x_1")
    for
      size      <- Gen.choose(2, 6)
      names     <- Gen.pick(size, pool)
      split     <- Gen.choose(1, size - 1)
      fields    <- Gen.sequence[Vector[FieldText], FieldText](names.toVector.map(field))
      noted     <- Gen.someOf(names.drop(split))
      note      <- Gen.oneOf("must be True or False", s"must be a single int value $Markup")
      objective <- Gen.listOf(Gen.oneOf(words, Gen.const(""), words.map("  " + _))).map(_.toVector)
      rest      <- Gen.oneOf(Gen.const(""), Gen.const(PolicyAdapter.FixedHeader), words)
    yield Message(fields.take(split), fields.drop(split), noted.map(_ -> note).toMap, objective, rest)

  // ── L27 ──

  property(
    "Instructions L27: the parts of a system message give back its fields, its order, its objective and its rest"
  ) {
    forAllNoShrink(messages)(m => assertEquals(Instructions.parse(m.text), Some(m.parts), m.text))
  }

  property(
    "Instructions L27: a text with no head line of the four, or with a line before the first field, has no parts"
  ) {
    forAllNoShrink(messages) { m =>
      Heads.foreach { head =>
        val cut = m.text.linesIterator.filterNot(_.startsWith(head)).mkString("\n")
        assertEquals(Instructions.parse(cut), None, s"without $head:\n$cut")
      }
      assertEquals(Instructions.parse(m.text.replaceFirst("\n", "\nFAKE-LINE\n")), None)
    }
  }

  property(
    "Instructions L27: any text gives parts or none with no error, and parts only after the head of the inputs"
  ) {
    forAllNoShrink(Gen.oneOf(text, messages.map(m => m.text.take(m.text.length / 2))))(t =>
      Instructions.parse(t).foreach(_ => assert(t.startsWith(Heads(0)), t))
    )
  }

  property("Instructions L27: the HTML of the parts has each field, each note, the objective and the rest, escaped") {
    forAllNoShrink(messages) { m =>
      val html = Instructions.html(m.parts)
      (m.inputs ++ m.outputs).foreach { f =>
        assert(html.contains(s"<td><code>${f.name}</code></td><td class=\"ty\">${Html.escape(f.kind)}</td>"), html)
      }
      m.notes.values.foreach(n => assert(html.contains(Html.escape(s"the value you produce $n")), html))
      assert(html.contains(s"""<div class="md">${Markdown.html(m.parts.objective)}</div>"""), html)
      if m.rest.nonEmpty then assert(html.contains(s"""<div class="rest">${Markdown.html(m.rest)}</div>"""), html)
      assert(!html.contains(Markup), html)
    }
  }

  test("Instructions L27: the HTML of the parts gives the objective, the fields, the format and the rest") {
    val parts = Instructions.parse(system(
      Vector(FieldText("question", "str", "The `question`.")),
      Vector(FieldText("answer", "bool", "")),
      Map("answer" -> "must be True or False"),
      Vector("Answer it."),
      "FAKE-REST"
    )).getOrElse(fail("no parts"))
    assertEquals(
      Instructions.html(parts),
      """<h3>The objective</h3><div class="md"><p>Answer it.</p></div>""" +
        """<h3>The input fields</h3><table class="fields"><tr><td><code>question</code></td><td class="ty">str</td>""" +
        """<td><p>The <code>question</code>.</p></td></tr></table>""" +
        """<h3>The output fields</h3><table class="fields"><tr><td><code>answer</code></td><td class="ty">bool</td>""" +
        """<td><div class="fnote">the value you produce must be True or False</div></td></tr></table>""" +
        """<h3>The format</h3><p>The fields come in this order, each after its marker, for example """ +
        """<code>[[ ## question ## ]]</code>: <code>question</code> → <code>answer</code> → <code>completed</code>.""" +
        """</p><div class="rest"><p>FAKE-REST</p></div>"""
    )
  }

  test("Instructions L27: a system message splits into the instructions and the fixed fields after them") {
    val head = system(
      Vector(FieldText("task", "str", "T.")),
      Vector(FieldText("text", "str", "")),
      Map.empty,
      Vector("Work."),
      PolicyAdapter.FixedHeader
    )
    val (instructions, fixed) =
      Instructions.split(s"$head\n\n[[ ## task ## ]]\nFAKE-TASK\n\n[[ ## memory ## ]]\nFAKE-MEMORY")
    assertEquals(instructions, head)
    assertEquals(fixed, Vector("task" -> "FAKE-TASK", "memory" -> "FAKE-MEMORY"))
    assertEquals(Instructions.split(head), (head, Vector.empty))
    assertEquals(Instructions.fieldHtml("task", "**a**"), """<div class="md ptask"><p><strong>a</strong></p></div>""")
    assertEquals(
      Instructions.fieldHtml("apiReference", "def f = 1"),
      s"""<pre class="ptext">${Highlight.sourceHtml("def f = 1")}</pre>"""
    )
    assertEquals(Instructions.fieldHtml("memory", Markup), s"""<pre class="ptext">${Html.escape(Markup)}</pre>""")
  }
