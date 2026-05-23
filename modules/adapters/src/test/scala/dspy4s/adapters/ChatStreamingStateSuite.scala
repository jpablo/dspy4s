package dspy4s.adapters

import dspy4s.adapters.contracts.FieldChunk
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import munit.FunSuite

class ChatStreamingStateSuite extends FunSuite:

  private def output(name: String): FieldSpec =
    FieldSpec(name = name, role = FieldRole.Output)

  /** Drives the state machine with a sequence of text deltas and returns every
    * chunk it emitted, including the final flush. */
  private def drive(state: ChatStreamingState, deltas: String*): Vector[FieldChunk] =
    deltas.iterator.flatMap(state.receive).toVector ++ state.finish()

  test("single-output signature: all text streams to the single field") {
    val state = new ChatStreamingState(Vector(output("answer")))
    val out = drive(state, "Hel", "lo, ", "world!")
    val text = out.filter(_.fieldName == "answer").map(_.text).mkString
    assertEquals(text, "Hello, world!")
    assert(out.exists(_.isLast), "stream must produce a last-marked chunk")
    assertEquals(out.last.isLast, true)
  }

  test("multi-output: splits on field labels and tags each chunk by field") {
    val state = new ChatStreamingState(Vector(output("reasoning"), output("answer")))
    val raw = "reasoning: think step by step\nanswer: 42"
    val out = drive(state, raw)
    val grouped = out.groupMapReduce(_.fieldName)(_.text)(_ + _)
    assertEquals(grouped.get("reasoning"), Some("think step by step"))
    assertEquals(grouped.get("answer"), Some("42"))
  }

  test("does not emit a partial line that could still become a label") {
    val state = new ChatStreamingState(Vector(output("reasoning"), output("answer")))
    // After "reasoning: foo\n" we see "ans" — could be the start of "answer:";
    // the state machine must hold it back.
    val afterFirst = state.receive("reasoning: foo\nans")
    val emittedSoFar = afterFirst.collect { case FieldChunk(_, t, _) => t }.mkString
    assert(!emittedSoFar.contains("ans"), s"emitted='${emittedSoFar}'")
    // Once the label completes and a value arrives, it routes to `answer`.
    val rest = state.receive("wer: 42")
    val finalChunks = state.finish()
    val grouped = (afterFirst ++ rest ++ finalChunks)
      .groupMapReduce(_.fieldName)(_.text)(_ + _)
    assertEquals(grouped.get("reasoning"), Some("foo"))
    assertEquals(grouped.get("answer"), Some("42"))
  }

  test("emits per-chunk segments as newlines arrive (token-by-token streaming)") {
    val state = new ChatStreamingState(Vector(output("answer")))
    // Token-by-token; chunks should accrue once a newline lets us flush.
    val r1 = state.receive("answer: He")
    val r2 = state.receive("llo")
    val r3 = state.receive("\nworld")
    val finalChunks = state.finish()
    val all = r1 ++ r2 ++ r3 ++ finalChunks
    val text = all.map(_.text).mkString
    assertEquals(text, "Hello\nworld")
  }

  test("finish() flushes the trailing buffer and marks the last chunk isLast") {
    val state = new ChatStreamingState(Vector(output("reasoning"), output("answer")))
    val streamed = state.receive("reasoning: a\nanswer: b") // no trailing newline
    val finalChunks = state.finish()
    val all = streamed ++ finalChunks
    val grouped = all.groupMapReduce(_.fieldName)(_.text)(_ + _)
    assertEquals(grouped.get("answer"), Some("b"))
    assertEquals(all.last.isLast, true)
    assertEquals(all.last.fieldName, "answer")
  }

  test("multi-output: text before the first known label is dropped") {
    val state = new ChatStreamingState(Vector(output("reasoning"), output("answer")))
    val out = drive(state, "Some preamble\nreasoning: real\nanswer: 1")
    val grouped = out.groupMapReduce(_.fieldName)(_.text)(_ + _)
    // No field exists for the preamble text, so it must not appear anywhere.
    assertEquals(grouped.values.mkString.contains("preamble"), false)
    assertEquals(grouped.get("reasoning"), Some("real"))
    assertEquals(grouped.get("answer"), Some("1"))
  }

  test("custom field prefix is honored") {
    val state = new ChatStreamingState(Vector(
      output("answer").copy(prefix = Some("Final Answer:")),
      output("rationale").copy(prefix = Some("Reasoning:"))
    ))
    val out = drive(state, "Reasoning: walked through it\nFinal Answer: 7")
    val grouped = out.groupMapReduce(_.fieldName)(_.text)(_ + _)
    assertEquals(grouped.get("rationale"), Some("walked through it"))
    assertEquals(grouped.get("answer"), Some("7"))
  }

  test("label matching is case-insensitive") {
    val state = new ChatStreamingState(Vector(output("answer")))
    val out = drive(state, "ANSWER: yes")
    val grouped = out.groupMapReduce(_.fieldName)(_.text)(_ + _)
    assertEquals(grouped.get("answer"), Some("yes"))
  }

  test("finish() is idempotent") {
    val state = new ChatStreamingState(Vector(output("answer")))
    state.receive("answer: x")
    val first = state.finish()
    val second = state.finish()
    assert(first.nonEmpty)
    assertEquals(second, Vector.empty[FieldChunk])
  }
