package dspy4s.adapters

import dspy4s.adapters.contracts.FieldChunk
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import munit.FunSuite

class ChatStreamingStateSuite extends FunSuite:

  private def output(name: String): FieldSpec =
    FieldSpec(name = name, role = FieldRole.Output)

  /** Drive the state with a sequence of receive() calls and finish at the end.
    * Returns every emitted FieldChunk in order. */
  private def drive(state: ChatStreamingState, deltas: String*): Vector[FieldChunk] =
    deltas.iterator.flatMap(state.receive).toVector ++ state.finish()

  private def collect(out: Vector[FieldChunk]): Map[String, String] =
    out.groupMapReduce(_.fieldName)(_.text)(_ + _)

  test("single-output signature: marker frames the answer field") {
    val state = new ChatStreamingState(Vector(output("answer")))
    val out = drive(state, "[[ ## answer ## ]]\nHello, world!\n[[ ## completed ## ]]")
    assertEquals(collect(out).get("answer"), Some("Hello, world!"))
    assertEquals(out.last.isLast, true)
  }

  test("multi-output: each marker switches the active field") {
    val state = new ChatStreamingState(Vector(output("reasoning"), output("answer")))
    val raw =
      """[[ ## reasoning ## ]]
        |think step by step
        |[[ ## answer ## ]]
        |42
        |[[ ## completed ## ]]""".stripMargin
    val out = drive(state, raw)
    val grouped = collect(out)
    assertEquals(grouped.get("reasoning"), Some("think step by step"))
    assertEquals(grouped.get("answer"), Some("42"))
  }

  test("multi-line field values arrive intact") {
    val state = new ChatStreamingState(Vector(output("reasoning"), output("answer")))
    val raw =
      """[[ ## reasoning ## ]]
        |line one
        |line two
        |line three
        |[[ ## answer ## ]]
        |42
        |[[ ## completed ## ]]""".stripMargin
    val out = drive(state, raw)
    val grouped = collect(out)
    assertEquals(grouped.get("reasoning"), Some("line one\nline two\nline three"))
    assertEquals(grouped.get("answer"), Some("42"))
  }

  test("partial marker arriving across receive() boundaries is held back") {
    val state = new ChatStreamingState(Vector(output("answer"), output("rationale")))
    val first = state.receive("[[ ## answer ## ]]\nfoo\n[[ ## ratio")
    val partialEmittedText = first.collect { case FieldChunk(_, t, _) => t }.mkString
    assert(!partialEmittedText.contains("[[ ## ratio"), s"emitted='$partialEmittedText'")

    val second = state.receive("nale ## ]]\nbar\n[[ ## completed ## ]]")
    val all = first ++ second ++ state.finish()
    val grouped = collect(all)
    assertEquals(grouped.get("answer"), Some("foo"))
    assertEquals(grouped.get("rationale"), Some("bar"))
  }

  test("colon-prefixed content lines are NOT interpreted as field labels") {
    // The key win over the old `prefix:` framing: stray text on its own line
    // that happens to look like a label is preserved as content.
    val state = new ChatStreamingState(Vector(output("reasoning"), output("answer")))
    val raw =
      """[[ ## reasoning ## ]]
        |Note: this is interesting.
        |Another line.
        |[[ ## answer ## ]]
        |42
        |[[ ## completed ## ]]""".stripMargin
    val out = drive(state, raw)
    val grouped = collect(out)
    assertEquals(grouped.get("reasoning"), Some("Note: this is interesting.\nAnother line."))
    assertEquals(grouped.get("answer"), Some("42"))
  }

  test("missing [[ ## completed ## ]] marker is tolerated; finish() flushes the active field") {
    val state = new ChatStreamingState(Vector(output("answer")))
    val out = drive(state, "[[ ## answer ## ]]\n42")
    assertEquals(collect(out).get("answer"), Some("42"))
    assertEquals(out.last.isLast, true)
  }

  test("text before the first marker is discarded (no fallback fields)") {
    val state = new ChatStreamingState(Vector(output("reasoning"), output("answer")))
    val out = drive(state, "Some preamble\n[[ ## reasoning ## ]]\nreal\n[[ ## answer ## ]]\n1\n[[ ## completed ## ]]")
    val grouped = collect(out)
    assertEquals(grouped.values.mkString.contains("preamble"), false)
    assertEquals(grouped.get("reasoning"), Some("real"))
    assertEquals(grouped.get("answer"), Some("1"))
  }

  test("single-output signature: text without any marker is attributed to the only output field") {
    val state = new ChatStreamingState(Vector(output("answer")))
    val out = drive(state, "just the value")
    assertEquals(collect(out).get("answer"), Some("just the value"))
  }

  test("unknown marker names are skipped without ending up as content") {
    val state = new ChatStreamingState(Vector(output("answer")))
    val out = drive(state, "[[ ## hallucination ## ]]\noh no\n[[ ## answer ## ]]\nok\n[[ ## completed ## ]]")
    val grouped = collect(out)
    assertEquals(grouped.get("answer"), Some("ok"))
    assert(!grouped.contains("hallucination"))
  }

  test("finish() is idempotent") {
    val state = new ChatStreamingState(Vector(output("answer")))
    state.receive("[[ ## answer ## ]]\nx")
    val first = state.finish()
    val second = state.finish()
    assert(first.nonEmpty)
    assertEquals(second, Vector.empty[FieldChunk])
  }

  test("token-by-token streaming joins chunks across many receive() calls") {
    val state = new ChatStreamingState(Vector(output("answer")))
    val parts = Vector("[[ ## a", "nswer ## ]]\nHe", "llo, ", "world", "\n[[ ## comp", "leted ## ]]")
    val out = parts.iterator.flatMap(state.receive).toVector ++ state.finish()
    assertEquals(collect(out).get("answer"), Some("Hello, world"))
  }
