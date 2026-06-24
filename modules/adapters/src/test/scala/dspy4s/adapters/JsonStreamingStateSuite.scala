package dspy4s.adapters

import dspy4s.adapters.contracts.FieldChunk
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import munit.FunSuite

class JsonStreamingStateSuite extends FunSuite:

  private def output(name: String): FieldSpec =
    FieldSpec(name = name, role = FieldRole.Output)

  private def drive(state: JsonStreamingState, deltas: String*): Vector[FieldChunk] =
    deltas.iterator.flatMap(state.receive).toVector ++ state.finish()

  private def collect(out: Vector[FieldChunk]): Map[String, String] =
    out.groupMapReduce(_.fieldName)(_.text)(_ + _)

  test("single string field: emits unescaped content") {
    val state = new JsonStreamingState(Vector(output("answer")))
    val out = drive(state, """{"answer": "Hello, world!"}""")
    assertEquals(collect(out).get("answer"), Some("Hello, world!"))
  }

  test("two string fields: emits each separately and marks the last chunk") {
    val state = new JsonStreamingState(Vector(output("reasoning"), output("answer")))
    val out = drive(state, """{"reasoning": "think", "answer": "42"}""")
    val grouped = collect(out)
    assertEquals(grouped.get("reasoning"), Some("think"))
    assertEquals(grouped.get("answer"), Some("42"))
    // Every field's stream must end on an isLast=true chunk.
    val perFieldLast = out.groupMap(_.fieldName)(_.isLast).view.mapValues(_.exists(identity)).toMap
    assertEquals(perFieldLast.get("reasoning"), Some(true))
    assertEquals(perFieldLast.get("answer"), Some(true))
  }

  test("non-string scalar values (number, bool, null) emit their literal text") {
    val state = new JsonStreamingState(Vector(output("n"), output("b"), output("nil")))
    val out = drive(state, """{"n": 42, "b": true, "nil": null}""")
    val grouped = collect(out)
    assertEquals(grouped.get("n"), Some("42"))
    assertEquals(grouped.get("b"), Some("true"))
    assertEquals(grouped.get("nil"), Some("null"))
  }

  test("string escapes are decoded: \\n, \\\", \\\\, \\t, \\uXXXX") {
    val state = new JsonStreamingState(Vector(output("v")))
    val out = drive(state, """{"v": "a\nb \"q\" \\ \tx é"}""")
    assertEquals(collect(out).get("v"), Some("a\nb \"q\" \\ \tx é"))
  }

  test("token-by-token streaming preserves content across receive boundaries") {
    val state = new JsonStreamingState(Vector(output("v")))
    val parts = Vector("""{"v"""", """": """", "Hel", "lo, ", "world", """"}""")
    val out = parts.iterator.flatMap(state.receive).toVector ++ state.finish()
    val joined = out.filter(_.fieldName == "v").map(_.text).mkString
    assertEquals(joined, "Hello, world")
  }

  test("token-by-token: \\uXXXX escape that spans receive() calls") {
    val state = new JsonStreamingState(Vector(output("v")))
    // The 4 hex chars of é land in three separate receive() calls.
    val out = state.receive("""{"v": "\u""") ++
      state.receive("00") ++
      state.receive("e9") ++
      state.receive("""hi"}""") ++
      state.finish()
    assertEquals(collect(out).get("v"), Some("éhi"))
  }

  test("malformed \\uXXXX escape degrades to literal text instead of crashing the stream") {
    // Regression: Integer.parseInt on non-hex digits threw NumberFormatException out of the consumer.
    val state = new JsonStreamingState(Vector(output("v")))
    val out = drive(state, """{"v": "x\uZZZZy"}""")
    assertEquals(collect(out).get("v"), Some("""x\uZZZZy"""))
  }

  test("fenced ```json preamble is tolerated (skips chars before the first '{')") {
    val state = new JsonStreamingState(Vector(output("answer")))
    val out = drive(state, "```json\n{\"answer\": \"42\"}\n```")
    assertEquals(collect(out).get("answer"), Some("42"))
  }

  test("nested object value emits the raw JSON sub-document") {
    val state = new JsonStreamingState(Vector(output("data")))
    val out = drive(state, """{"data": {"a": 1, "b": [2, 3]}}""")
    assertEquals(collect(out).get("data"), Some("""{"a": 1, "b": [2, 3]}"""))
  }

  test("unknown signature keys parse cleanly but produce no chunks") {
    val state = new JsonStreamingState(Vector(output("known")))
    val out = drive(state, """{"unknown": "x", "known": "y"}""")
    val grouped = collect(out)
    assertEquals(grouped.get("known"), Some("y"))
    assert(!grouped.contains("unknown"), grouped.toString)
  }

  test("string containing brace and quote characters does not confuse the parser") {
    val state = new JsonStreamingState(Vector(output("v"), output("w")))
    val out = drive(state, """{"v": "has { and \" inside", "w": "ok"}""")
    val grouped = collect(out)
    assertEquals(grouped.get("v"), Some("has { and \" inside"))
    assertEquals(grouped.get("w"), Some("ok"))
  }

  test("finish() flushes a value that the model truncated mid-stream") {
    val state = new JsonStreamingState(Vector(output("v")))
    val _ = state.receive("""{"v": "abc""") // string never closes
    val flushed = state.finish()
    val text = flushed.filter(_.fieldName == "v").map(_.text).mkString
    assertEquals(text, "abc")
    assertEquals(flushed.lastOption.map(_.isLast), Some(true))
  }

  test("finish() is idempotent") {
    val state = new JsonStreamingState(Vector(output("v")))
    val _ = state.receive("""{"v": "abc""") // truncated — value still open at stream end
    val first = state.finish()
    val second = state.finish()
    assert(first.nonEmpty)
    assertEquals(second, Vector.empty[FieldChunk])
  }
