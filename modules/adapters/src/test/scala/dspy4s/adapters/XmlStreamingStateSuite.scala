package dspy4s.adapters

import dspy4s.adapters.contracts.FieldChunk
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import munit.FunSuite

class XmlStreamingStateSuite extends FunSuite:

  private def output(name: String): FieldSpec =
    FieldSpec(name = name, role = FieldRole.Output)

  private def drive(state: XmlStreamingState, deltas: String*): Vector[FieldChunk] =
    deltas.iterator.flatMap(state.receive).toVector ++ state.finish()

  private def collect(out: Vector[FieldChunk]): Map[String, String] =
    out.groupMapReduce(_.fieldName)(_.text)(_ + _)

  test("single field: emits content between tags") {
    val state = new XmlStreamingState(Vector(output("answer")))
    val out = drive(state, "<outputs><answer>42</answer></outputs>")
    assertEquals(collect(out).get("answer"), Some("42"))
  }

  test("two fields: emits each separately and marks the last chunk") {
    val state = new XmlStreamingState(Vector(output("reasoning"), output("answer")))
    val out = drive(state, "<outputs><reasoning>think</reasoning><answer>42</answer></outputs>")
    val grouped = collect(out)
    assertEquals(grouped.get("reasoning"), Some("think"))
    assertEquals(grouped.get("answer"), Some("42"))
    val perFieldLast = out.groupMap(_.fieldName)(_.isLast).view.mapValues(_.exists(identity)).toMap
    assertEquals(perFieldLast.get("reasoning"), Some(true))
    assertEquals(perFieldLast.get("answer"), Some(true))
  }

  test("decodes named XML entities") {
    val state = new XmlStreamingState(Vector(output("v")))
    val out = drive(state, "<outputs><v>a &amp; b &lt; c &gt; d &quot;e&quot; &apos;f&apos;</v></outputs>")
    assertEquals(collect(out).get("v"), Some("a & b < c > d \"e\" 'f'"))
  }

  test("decodes numeric character references") {
    val state = new XmlStreamingState(Vector(output("v")))
    val out = drive(state, "<outputs><v>&#233; and &#x00E9;</v></outputs>")
    assertEquals(collect(out).get("v"), Some("é and é"))
  }

  test("token-by-token streaming joins content correctly") {
    val state = new XmlStreamingState(Vector(output("v")))
    val parts = Vector("<outputs><v>Hel", "lo, ", "world</v>", "</outputs>")
    val out = parts.iterator.flatMap(state.receive).toVector ++ state.finish()
    val joined = out.filter(_.fieldName == "v").map(_.text).mkString
    assertEquals(joined, "Hello, world")
  }

  test("entity that spans receive() boundaries") {
    val state = new XmlStreamingState(Vector(output("v")))
    val out = state.receive("<outputs><v>a&am") ++
      state.receive("p;b</v></outputs>") ++
      state.finish()
    assertEquals(collect(out).get("v"), Some("a&b"))
  }

  test("close tag split across receive() calls") {
    val state = new XmlStreamingState(Vector(output("v")))
    val out = state.receive("<outputs><v>data") ++
      state.receive("</v></outputs>") ++
      state.finish()
    assertEquals(collect(out).get("v"), Some("data"))
  }

  test("fenced ```xml preamble is tolerated") {
    val state = new XmlStreamingState(Vector(output("v")))
    val out = drive(state, "```xml\n<outputs><v>ok</v></outputs>\n```")
    assertEquals(collect(out).get("v"), Some("ok"))
  }

  test("unknown tags are skipped without emission") {
    val state = new XmlStreamingState(Vector(output("known")))
    val out = drive(state, "<outputs><other>x</other><known>y</known></outputs>")
    val grouped = collect(out)
    assertEquals(grouped.get("known"), Some("y"))
    assert(!grouped.contains("other"), grouped.toString)
  }

  test("whitespace between tags is ignored") {
    val state = new XmlStreamingState(Vector(output("v")))
    val out = drive(state, "<outputs>\n  <v>ok</v>\n</outputs>")
    assertEquals(collect(out).get("v"), Some("ok"))
  }

  test("finish() flushes a value that was truncated mid-content") {
    val state = new XmlStreamingState(Vector(output("v")))
    val _ = state.receive("<outputs><v>abc") // close tag never arrived
    val flushed = state.finish()
    val text = flushed.filter(_.fieldName == "v").map(_.text).mkString
    assertEquals(text, "abc")
    assertEquals(flushed.lastOption.map(_.isLast), Some(true))
  }

  test("finish() is idempotent") {
    val state = new XmlStreamingState(Vector(output("v")))
    val _ = state.receive("<outputs><v>abc")
    val first = state.finish()
    val second = state.finish()
    assert(first.nonEmpty)
    assertEquals(second, Vector.empty[FieldChunk])
  }
